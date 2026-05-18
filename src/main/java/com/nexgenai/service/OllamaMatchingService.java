package com.nexgenai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
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

    // ── Prompt ultra-court = réponse ultra-rapide ─────────────────────────────
    private static final String SYSTEM_PROMPT = """
        HR expert. Analyze CV vs job. Reply ONLY valid JSON. No markdown.
        """;

    // ─── Analyse CV ↔ Job ─────────────────────────────────────────────────────
    public MatchResult analyze(String cvText, String offerText) {
        String prompt = buildCompactPrompt(cvText, offerText);

        Map<String, Object> payload = new HashMap<>();
        payload.put("model",  ollamaModel);
        payload.put("system", SYSTEM_PROMPT);
        payload.put("prompt", prompt);
        payload.put("stream", false);
        payload.put("format", "json");
        payload.put("options", Map.of(
            "temperature",  0.0,   // ← Déterministe = plus rapide, pas d'hésitation
            "num_predict",  500,   // ← MAX 500 tokens (au lieu de 1500) = 3x plus rapide
            "num_ctx",      2048,  // ← Contexte réduit = moins de mémoire = plus rapide
            "top_k",        10,    // ← Moins d'options = plus rapide
            "top_p",        0.5,   // ← Sampling restreint
            "repeat_penalty", 1.0  // ← Pas de pénalité = plus rapide
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

    // ─── Prompt compact — moins de tokens = BEAUCOUP plus rapide ─────────────
    private String buildCompactPrompt(String cvText, String offerText) {
        // ⚡ ASTUCE CLEF : tronquer agressivement le CV et l'offre
        // Mistral:7b n'a pas besoin de tout lire pour scorer
        String cv    = cvText.length()    > 1500 ? cvText.substring(0, 1500)    : cvText;
        String offer = offerText.length() > 800  ? offerText.substring(0, 800)  : offerText;

        return """
            CV:
            %s

            JOB:
            %s

            JSON ONLY:
            {
              "score_global": <0-100>,
              "verdict": "<5 words FR>",
              "resume": "<2 sentences FR>",
              "dimensions": {
                "competences_techniques": {"score": <0-100>},
                "experience":             {"score": <0-100>},
                "formation":              {"score": <0-100>},
                "soft_skills":            {"score": <0-100>},
                "secteur_industrie":      {"score": <0-100>},
                "localisation_langue":    {"score": <0-100>}
              },
              "competences_matchees":   ["skill1","skill2"],
              "competences_manquantes": ["skill1","skill2"]
            }
            """.formatted(cv, offer);
    }

    // ─── Vérifie la disponibilité d'Ollama ───────────────────────────────────
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
    @SuppressWarnings("unchecked")
    private MatchResult parseResult(String raw) {
        try {
            String cleaned = raw.replaceAll("```json\\s*|```\\s*", "").trim();
            int start = cleaned.indexOf("{");
            int end   = cleaned.lastIndexOf("}") + 1;
            if (start == -1 || end == 0) throw new RuntimeException("No JSON found");

            Map<String, Object> data = objectMapper.readValue(
                cleaned.substring(start, end), Map.class);

            MatchResult result = new MatchResult();
            result.setScoreGlobal(toInt(data.get("score_global")));
            result.setVerdict(str(data.get("verdict")));
            result.setResume(str(data.get("resume")));

            // ── Dimensions — gère les deux formats: {score:75} et 75 ──────────
            if (data.get("dimensions") instanceof Map<?,?> dims) {
                // Normaliser: forcer chaque valeur en {"score": N}
                Map<String, Object> normalized = new java.util.LinkedHashMap<>();
                dims.forEach((k, v) -> {
                    if (v instanceof Number n) {
                        normalized.put(k.toString(), Map.of("score", n.intValue()));
                    } else if (v instanceof Map<?,?> m) {
                        // Extraire le score du sous-objet
                        Object s = ((Map<?,?>)m).get("score");
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

            log.info("✅ Score parsé: {} — {}", result.getScoreGlobal(), result.getVerdict());
            return result;

        } catch (Exception e) {
            log.warn("⚠️ Parse failed: {} — raw: {}", e.getMessage(),
                raw != null && raw.length() > 200 ? raw.substring(0, 200) : raw);
            return fallbackResult();
        }
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
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
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