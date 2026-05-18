package com.nexgenai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexgenai.model.Candidate;
import com.nexgenai.model.ChatSession;
import com.nexgenai.model.Job;
import com.nexgenai.repository.ChatSessionRepository;
import com.nexgenai.repository.JobRepository;
import com.nexgenai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatbotService {

    private final ChatSessionRepository chatSessionRepository;
    private final UserRepository userRepository;
    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.chat-model:mistral:7b}")
    private String chatModel;

    private static final int MAX_QUESTIONS = 5;
    
    private static final Pattern COMMENT_PATTERN = Pattern.compile(
        "(?i)(merci|intéressant|bien|parfait|super|excellent|d'accord|noté|compris|parfait|ok|d'accord)",
        Pattern.MULTILINE
    );

    @Transactional
    public InitResult initSession(String candidateEmail, String jobId) {
        Candidate candidate = findCandidate(candidateEmail);
        Job job = jobRepository.findById(jobId)
            .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

        Optional<ChatSession> existing = chatSessionRepository
            .findByCandidateIdAndJobId(candidate.getId(), jobId);
        if (existing.isPresent() && !existing.get().isDone()) {
            ChatSession s = existing.get();
            List<Map<String, String>> history = fromJson(s.getMessagesJson());
            return new InitResult(s.getId(), s.getQuestionCount(), visibleMessages(history));
        }

        String q1 = buildQ1(candidate, job);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt()));
        messages.add(Map.of("role", "assistant", "content", q1));

        ChatSession session = ChatSession.builder()
            .candidateId(candidate.getId())
            .jobId(jobId)
            .messagesJson(toJson(messages))
            .questionCount(1)
            .done(false)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

        ChatSession saved = chatSessionRepository.save(session);
        log.info("✅ Session créée: {}", saved.getId());

        return new InitResult(saved.getId(), 1,
            List.of(Map.of("role", "assistant", "content", q1)));
    }

    @Transactional
    public ChatResponse processMessage(String sessionId, String userMessage) {
        ChatSession session = chatSessionRepository.findById(sessionId)
            .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        if (session.isDone()) {
            return new ChatResponse("Entretien terminé. Merci !", true,
                session.getInterviewScore(), null);
        }

        List<Map<String, String>> messages = fromJson(session.getMessagesJson());
        messages.add(Map.of("role", "user", "content", userMessage));

        int currentQuestion = session.getQuestionCount();
        boolean done = false;
        Integer score = null;
        String aiReply;

        if (currentQuestion >= MAX_QUESTIONS) {
            aiReply = generateScore(messages);
            score = extractScore(aiReply);
            done = true;
            log.info("🏁 Interview terminée - Score: {}", score);
        } else {
            aiReply = generateNextQuestion(messages, currentQuestion + 1);
            
            if (containsComment(aiReply)) {
                log.warn("⚠️ Commentaire détecté, fallback question {}", currentQuestion + 1);
                aiReply = getFallbackQuestion(currentQuestion + 1);
            }
            
            session.setQuestionCount(currentQuestion + 1);
        }

        messages.add(Map.of("role", "assistant", "content", aiReply));
        
        session.setMessagesJson(toJson(messages));
        session.setDone(done);
        if (score != null) session.setInterviewScore(score);
        session.setUpdatedAt(LocalDateTime.now());
        chatSessionRepository.save(session);

        return new ChatResponse(aiReply, done, score, null);
    }

    private String generateNextQuestion(List<Map<String, String>> messages, int qNum) {
        String context = buildMinimalContext(messages);
        
        // Sélection sécurisée du thème
        String theme = getSafeTheme(qNum);
        
        String prompt = buildStrictQuestionPrompt(qNum, theme, context);
        String result = callOllamaStrict(prompt);
        result = cleanQuestion(result);
        
        if (!isValidQuestion(result)) {
            log.warn("❌ Question invalide générée pour q{}: '{}'", qNum, result);
            result = getFallbackQuestion(qNum);
        }
        
        log.debug("📝 Question {}: {}", qNum, result);
        return result;
    }

    private String getSafeTheme(int qNum) {
        // Map sécurisée avec des thèmes par défaut
        Map<Integer, String> themeMap = new HashMap<>();
        themeMap.put(2, "technologies maîtrisées et stack technique");
        themeMap.put(3, "projet récent et votre rôle précis");
        themeMap.put(4, "disponibilité, prétentions et modalités de travail");
        themeMap.put(5, "motivation pour ce poste et cette entreprise");
        
        return themeMap.getOrDefault(qNum, "expérience pertinente pour le poste");
    }

    private String buildStrictQuestionPrompt(int qNum, String theme, String context) {
        return String.format(
            "INSTRUCTION STRICTE - AUCUNE EXCEPTION\n" +
            "Tu es un bot de recrutement. Tu DOIS POSER UNE QUESTION UNIQUEMENT.\n\n" +
            "RÈGLES ABSOLUES:\n" +
            "1. NE DIS RIEN D'AUTRE QUE LA QUESTION\n" +
            "2. NE DIS PAS merci, intéressant, bien, parfait, super, d'accord, ok\n" +
            "3. NE COMMENTE PAS la réponse précédente\n" +
            "4. NE FAIS PAS d'évaluation\n" +
            "5. N'ÉCRIS AUCUN TEXTE AVANT OU APRÈS LA QUESTION\n" +
            "6. LA QUESTION DOIT ÊTRE UNE SEULE PHRASE DE MOINS DE 20 MOTS\n" +
            "7. LA QUESTION DOIT SE TERMINER PAR UN POINT D'INTERROGATION\n\n" +
            "Contexte:\n%s\n\n" +
            "Question %d/%d: %s\n\n" +
            "RÉPONDS UNIQUEMENT PAR LA QUESTION, RIEN D'AUTRE:",
            context, qNum, MAX_QUESTIONS, theme
        );
    }

    private String generateScore(List<Map<String, String>> messages) {
        String conversation = extractConversation(messages);
        
        String prompt = String.format(
            "Évalue le candidat et donne UNIQUEMENT une note.\n\n" +
            "Conversation:\n%s\n\n" +
            "RÈGLES:\n" +
            "1. Note de 0 à 100\n" +
            "2. Format exact: [SCORE:XX]\n" +
            "3. AUCUN autre texte\n\n" +
            "RÉPONSE:",
            conversation
        );
        
        String result = callOllamaStrict(prompt);
        
        if (result.contains("[SCORE:")) {
            return result;
        }
        
        return "[SCORE:65]";
    }

    private String callOllamaStrict(String prompt) {
        try {
            List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", prompt)
            );
            
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", chatModel);
            payload.put("messages", messages);
            payload.put("stream", false);
            payload.put("options", Map.of(
                "temperature", 0.0,
                "num_predict", 50,
                "num_ctx", 256,
                "top_k", 1,
                "top_p", 0.1,
                "repeat_penalty", 2.0
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                ollamaBaseUrl + "/api/chat",
                new HttpEntity<>(payload, headers),
                Map.class
            );

            if (response.getBody() != null) {
                Object msgObj = response.getBody().get("message");
                if (msgObj instanceof Map<?, ?> msg) {
                    Object content = msg.get("content");
                    if (content instanceof String s) {
                        return s.trim();
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Erreur Ollama: {}", e.getMessage());
        }
        return "";
    }

    private String cleanQuestion(String text) {
        if (text == null || text.isBlank()) return "";
        
        String[] lines = text.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.endsWith("?") && !containsComment(line)) {
                line = line.replaceAll("(?i)^(question\\s*\\d*\\s*[:.]?\\s*)", "");
                line = line.replaceAll("(?i)^(ma question[:]?\\s*)", "");
                return line;
            }
        }
        
        for (String line : lines) {
            line = line.trim();
            if (!line.isBlank() && !containsComment(line)) {
                return line;
            }
        }
        
        return "";
    }

    private boolean containsComment(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("merci") || 
               lower.contains("intéressant") ||
               lower.contains("bien") ||
               lower.contains("parfait") ||
               lower.contains("super") ||
               lower.contains("excellent") ||
               lower.contains("d'accord") ||
               lower.contains("noté") ||
               lower.contains("compris") ||
               lower.contains("ok");
    }

    private boolean isValidQuestion(String text) {
        if (text == null || text.isBlank()) return false;
        return text.endsWith("?") && 
               text.split("\\s+").length >= 2 &&
               !containsComment(text);
    }

    private String buildMinimalContext(List<Map<String, String>> messages) {
        StringBuilder sb = new StringBuilder();
        List<Map<String, String>> recent = messages.stream()
            .filter(m -> !"system".equals(m.get("role")))
            .skip(Math.max(0, messages.size() - 7))
            .toList();
        
        for (Map<String, String> msg : recent) {
            String role = msg.get("role");
            String content = msg.get("content");
            if ("user".equals(role)) {
                sb.append("Candidat: ").append(content).append("\n");
            } else if ("assistant".equals(role)) {
                sb.append("Question: ").append(content).append("\n");
            }
        }
        return sb.toString();
    }

    private String extractConversation(List<Map<String, String>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> msg : messages) {
            if ("system".equals(msg.get("role"))) continue;
            String role = "assistant".equals(msg.get("role")) ? "Question" : "Réponse";
            sb.append(role).append(": ").append(msg.get("content")).append("\n");
        }
        return sb.toString();
    }

    private String getFallbackQuestion(int qNum) {
        return switch (qNum) {
            case 2 -> "Quelles technologies maîtrisez-vous ?";
            case 3 -> "Décrivez votre dernier projet en détail.";
            case 4 -> "Quelle est votre disponibilité ?";
            case 5 -> "Pourquoi ce poste vous intéresse-t-il ?";
            default -> "Parlez-moi de votre expérience.";
        };
    }

    private String buildQ1(Candidate candidate, Job job) {
        String name = candidate.getFirstName() != null ? candidate.getFirstName() : "";
        String current = candidate.getCurrentPosition();
        String title = job.getTitle();

        if (current != null && !current.isBlank()) {
            return String.format("Bonjour %s. Vous êtes actuellement %s. " +
                "Pourquoi postulez-vous au poste de %s ?",
                name, current, title);
        }
        return String.format("Bonjour %s. Décrivez votre expérience la plus pertinente " +
            "pour le poste de %s.",
            name, title);
    }

    private String buildSystemPrompt() {
        return "Tu es un bot de pré-recrutement.\n" +
               "RÈGLES IMPÉRATIVES:\n" +
               "1. Pose UNE question courte en français\n" +
               "2. NE COMMENTE JAMAIS les réponses\n" +
               "3. NE DIS PAS merci, intéressant, bien, parfait\n" +
               "4. N'ÉCRIS RIEN d'autre que la question\n" +
               "5. La question doit se terminer par ?\n" +
               "6. MAX 15 mots par question";
    }

    private Candidate findCandidate(String email) {
        return userRepository.findByEmail(email)
            .filter(u -> u instanceof Candidate)
            .map(u -> (Candidate) u)
            .orElseThrow(() -> new RuntimeException("Candidate not found: " + email));
    }

    private Integer extractScore(String text) {
        if (text == null) return 65;
        var m = Pattern.compile("\\[SCORE:(\\d+)]", Pattern.CASE_INSENSITIVE)
            .matcher(text);
        if (m.find()) {
            try { 
                return Math.min(100, Math.max(0, Integer.parseInt(m.group(1)))); 
            } catch (NumberFormatException ignored) {}
        }
        return 65;
    }

    private String toJson(Object obj) {
        try { 
            return objectMapper.writeValueAsString(obj); 
        } catch (Exception e) { 
            return "[]"; 
        }
    }

    private List<Map<String, String>> fromJson(String json) {
        try { 
            return objectMapper.readValue(json, new TypeReference<>() {}); 
        } catch (Exception e) { 
            return new ArrayList<>(); 
        }
    }

    private List<Map<String, String>> visibleMessages(List<Map<String, String>> messages) {
        return messages.stream()
            .filter(m -> !"system".equals(m.get("role")))
            .toList();
    }

    public record ChatResponse(String reply, boolean done, Integer score, String verdict) {}
    public record InitResult(String sessionId, int questionCount, List<Map<String, String>> history) {}
}