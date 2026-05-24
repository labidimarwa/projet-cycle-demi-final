package com.nexgenai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.nexgenai.model.TechnicalSkill;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OllamaMatchingService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:mistral:7b}")
    private String ollamaModel;

    // ─── System prompt : donne un rôle clair et des règles de raisonnement ────
    private static final String SYSTEM_PROMPT = """
        Tu es un expert RH senior avec 20 ans d'expérience en recrutement tech.
        Tu analyses des CV et des offres d'emploi avec une grande rigueur.
        
        RÈGLES DE RAISONNEMENT OBLIGATOIRES :
        
        1. FORMATION — Compare le niveau réel du candidat avec le niveau exigé :
           - Bac+3 (Licence/Bachelor) quand Bac+5 requis → score formation MAX 25/100
           - Bac+4 quand Bac+5 requis → score formation MAX 60/100
           - Bac+5 ou plus quand Bac+5 requis → 100/100
           - Si le niveau n'est pas mentionné dans le CV → 40/100 (inconnu)
           - Un "ingénieur" ou "Master" = Bac+5. Une "Licence" ou "Bachelor" = Bac+3.
        
        2. EXPÉRIENCE — Calcule les années réelles depuis les dates du CV :
           - Additionne UNIQUEMENT les postes professionnels (CDI, CDD, freelance)
           - Les stages comptent pour 30% de leur durée réelle
           - "Junior" = moins de 2 ans. "Confirmé" = 2-5 ans. "Senior" = 5+ ans.
           - Compare avec ce que demande le poste et pénalise si insuffisant.
        
        3. COMPÉTENCES — Croise exactement les skills du CV avec les skills requis :
           - Compte les skills requis qui sont présents dans le CV
           - Score = (skills présents / skills totaux requis) × 100
           - Sois honnête : ne présume pas qu'un skill est présent s'il n'est pas mentionné.
        
        4. NE JAMAIS donner 100/100 si une exigence clé n'est pas remplie.
        5. NE JAMAIS inventer des compétences qui ne sont pas dans le CV.
        6. Réponds UNIQUEMENT en JSON valide. Aucun texte avant ou après.
        """;

    // ─── Analyse CV ↔ Job ─────────────────────────────────────────────────────
    public MatchResult analyze(String cvText, String offerText) {
        String prompt = buildChainOfThoughtPrompt(cvText, offerText);

        Map<String, Object> payload = new HashMap<>();
        payload.put("model",  ollamaModel);
        payload.put("system", SYSTEM_PROMPT);
        payload.put("prompt", prompt);
        payload.put("stream", false);
        payload.put("options", Map.of(
        	    "temperature",    0.1,
        	    "num_predict",    2000,  // était 1000 → trop court
        	    "num_ctx",        3000,
        	    "top_k",          20,
        	    "top_p",          0.7,
        	    "repeat_penalty", 1.1
        	));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            long start = System.currentTimeMillis();
            ResponseEntity<Map> response = restTemplate.postForEntity(
                ollamaBaseUrl + "/api/generate",
                new HttpEntity<>(payload, headers),
                Map.class
            );
            long elapsed = System.currentTimeMillis() - start;
            log.info("⚡ Ollama répondu en {}ms", elapsed);

            String raw = (String) response.getBody().get("response");
            return parseResult(raw);

        } catch (Exception e) {
            log.error("❌ Ollama error: {}", e.getMessage());
            return fallbackResult();
        }
    }

    // ─── Prompt chain-of-thought : force le modèle à raisonner avant de scorer ─
    private String buildChainOfThoughtPrompt(String cvText, String offerText) {
    	// Ligne à modifier dans buildChainOfThoughtPrompt() :
    	String cv    = cvText.length()    > 1500 ? cvText.substring(0, 1500) : cvText;
    	String offer = offerText.length() > 700  ? offerText.substring(0, 700)  : offerText;

        return """
            === OFFRE D'EMPLOI ===
            %s
            
            === CV DU CANDIDAT ===
            %s
            
            === TON ANALYSE (raisonne étape par étape) ===
            
            ÉTAPE 1 - FORMATION :
            Quel diplôme est exigé par le poste ? (ex: Bac+5)
            Quel diplôme a le candidat ? (ex: Bac+3 Licence)
            → Le candidat remplit-il l'exigence ? OUI / NON / PARTIEL
            → Score formation (selon les règles du système) : ?/100
            
            ÉTAPE 2 - EXPÉRIENCE :
            Quelle expérience est exigée ? (en années)
            Quels postes et dates vois-tu dans le CV ? Liste-les.
            Combien d'années professionnelles effectives ? (hors stages)
            → Score expérience : ?/100
            
            ÉTAPE 3 - COMPÉTENCES :
            Quels skills sont exigés par le poste ? Liste-les.
            Lesquels sont présents dans le CV ? Liste-les.
            Lesquels sont absents ? Liste-les.
            → Score compétences : ?/100
            
            ÉTAPE 4 - SOFT SKILLS & ADÉQUATION GLOBALE :
            Le profil du candidat correspond-il à la culture / au secteur du poste ?
            → Score soft skills : ?/100
            
            ÉTAPE 5 - JSON FINAL :
            En te basant sur ton raisonnement ci-dessus, génère ce JSON :
            {
              "score_global": <moyenne pondérée : formation 25%% + expérience 30%% + compétences 30%% + soft 15%%>,
              "verdict": "<verdict honnête en 5 mots max en français>",
              "resume": "<2 phrases résumant honnêtement l'adéquation en français>",
              "dimensions": {
                "competences_techniques": {"score": <score de l'étape 3>},
                "experience":             {"score": <score de l'étape 2>},
                "formation":              {"score": <score de l'étape 1>},
                "soft_skills":            {"score": <score de l'étape 4>},
                "secteur_industrie":      {"score": <0-100>},
                "localisation_langue":    {"score": <0-100>}
              },
              "competences_matchees":   [<liste des skills présents>],
              "competences_manquantes": [<liste des skills absents>]
            }
            """.formatted(offer, cv);
    }

    // ─── Vérification Ollama ──────────────────────────────────────────────────
    public boolean isAvailable() {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(
                ollamaBaseUrl + "/api/tags", Map.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    // ─── Parse JSON ───────────────────────────────────────────────────────────
    // Le modèle peut répondre avec son raisonnement PUIS le JSON.
    // On extrait le dernier bloc JSON valide.

    
    
    private MatchResult parseResult(String raw) {
        try {
            log.debug("🔍 Réponse brute Ollama:\n{}", 
                raw != null && raw.length() > 500 ? raw.substring(0, 500) : raw);

            if (raw == null || raw.isBlank()) 
                throw new RuntimeException("Réponse vide");

            String cleaned = raw.replaceAll("```json\\s*|```\\s*", "").trim();

            // Trouver le début du JSON principal
            int start = cleaned.indexOf("{");
            if (start == -1) 
                throw new RuntimeException("Aucun JSON trouvé");

            String jsonStr = cleaned.substring(start);

            // ── Réparer JSON tronqué ──────────────────────────────────────
            jsonStr = repairTruncatedJson(jsonStr);

            Map<String, Object> data = objectMapper.readValue(jsonStr, Map.class);

            MatchResult result = new MatchResult();
            result.setScoreGlobal(toInt(data.get("score_global")));
            result.setVerdict(str(data.get("verdict")));
            result.setResume(str(data.get("resume")));

            if (data.get("dimensions") instanceof Map<?,?> dims) {
                Map<String, Object> normalized = new java.util.LinkedHashMap<>();
                dims.forEach((k, v) -> {
                    if (v instanceof Number n) {
                        normalized.put(k.toString(), Map.of("score", n.intValue()));
                    } else if (v instanceof Map<?,?> m) {
                        Object s = m.get("score");
                        normalized.put(k.toString(), Map.of("score", toInt(s)));
                    } else {
                        normalized.put(k.toString(), Map.of("score", 0));
                    }
                });
                result.setDimensionsJson(objectMapper.writeValueAsString(normalized));
            } else {
                result.setDimensionsJson("{}");
            }

            result.setSkillsMatched(objectMapper.writeValueAsString(
                data.getOrDefault("competences_matchees", java.util.List.of())));
            result.setSkillsMissing(objectMapper.writeValueAsString(
                data.getOrDefault("competences_manquantes", java.util.List.of())));

            if (result.getScoreGlobal() >= 95)
                log.warn("⚠️ Score suspicieusement haut ({}%)", result.getScoreGlobal());

            log.info("✅ Score parsé: {}% — {}", result.getScoreGlobal(), result.getVerdict());
            return result;

        } catch (Exception e) {
            log.warn("⚠️ Parse échoué: {} — raw[:300]: {}",
                e.getMessage(),
                raw != null && raw.length() > 300 ? raw.substring(0, 300) : raw);
            return fallbackResult();
        }
    }

    // ── Répare un JSON tronqué en fermant les accolades/crochets manquants ──
    private String repairTruncatedJson(String json) {
        // Supprimer trailing virgule/texte après le dernier } ou ]
        // Compter les accolades et crochets ouverts
        int braces   = 0;
        int brackets = 0;
        boolean inString = false;
        char prev = 0;

        for (char c : json.toCharArray()) {
            if (c == '"' && prev != '\\') inString = !inString;
            if (!inString) {
                if (c == '{') braces++;
                else if (c == '}') braces--;
                else if (c == '[') brackets++;
                else if (c == ']') brackets--;
            }
            prev = c;
        }

        // Supprimer trailing virgule si présente
        String trimmed = json.stripTrailing();
        if (trimmed.endsWith(",")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        // Fermer les structures ouvertes
        StringBuilder sb = new StringBuilder(trimmed);
        for (int i = 0; i < brackets; i++) sb.append("]");
        for (int i = 0; i < braces;   i++) sb.append("}");

        return sb.toString();
    }
    
    
    
    public MatchResult computeKeywordScore(String cvText, List<TechnicalSkill> skills) {
        if (skills == null || skills.isEmpty()) {
            MatchResult r = new MatchResult();
            r.setScoreGlobal(0);
            r.setVerdict("Score basique (IA indisponible)");
            r.setResume("Aucune compétence définie pour ce poste. Démarrez Ollama pour une analyse complète.");
            r.setDimensionsJson("{}");
            r.setSkillsMatched("[]");
            r.setSkillsMissing("[]");
            return r;
        }
        String cv = cvText.toLowerCase();
        int totalWeight = 0, matchedWeight = 0;
        List<String> matched = new ArrayList<>(), missing = new ArrayList<>();
        for (TechnicalSkill s : skills) {
            int w = (s.getWeight() != null && s.getWeight() > 0) ? s.getWeight() : 10;
            totalWeight += w;
            if (cv.contains(s.getName().toLowerCase())) {
                matchedWeight += w;
                matched.add(s.getName());
            } else {
                missing.add(s.getName());
            }
        }
        int score = totalWeight > 0 ? (matchedWeight * 100 / totalWeight) : 0;
        MatchResult r = new MatchResult();
        r.setScoreGlobal(score);
        r.setVerdict("Score basique (IA indisponible)");
        r.setResume("Correspondance de mots-clés. Démarrez Ollama pour une analyse IA complète.");
        r.setDimensionsJson("{}");
        r.setSkillsMatched(matched.isEmpty() ? "[]" : "[\"" + String.join("\",\"", matched) + "\"]");
        r.setSkillsMissing(missing.isEmpty() ? "[]" : "[\"" + String.join("\",\"", missing) + "\"]");
        return r;
    }

    private MatchResult fallbackResult() {
        MatchResult r = new MatchResult();
        r.setScoreGlobal(0);
        r.setVerdict("Analyse indisponible");
        r.setResume("Ollama non disponible. Lancez : ollama serve");
        r.setDimensionsJson("{}");
        r.setSkillsMatched("[]");
        r.setSkillsMissing("[]");
        return r;
    }

    private int toInt(Object o) {
        if (o instanceof Integer i) return i;
        if (o instanceof Number  n) return n.intValue();
        if (o instanceof String  s) {
            s = s.trim();
            if (s.contains("/")) s = s.split("/")[0].trim();   // "65/100" → "65"
            if (s.endsWith("%")) s = s.substring(0, s.length() - 1).trim();  // "65%" → "65"
            try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
        }
        return 0;
    }
    private String str(Object o) { return o == null ? "" : String.valueOf(o); }

    @lombok.Data
    public static class MatchResult {
        private int    scoreGlobal;
        private String verdict;
        private String resume;
        private String dimensionsJson;
        private String skillsMatched;
        private String skillsMissing;
    }
}