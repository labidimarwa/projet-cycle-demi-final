package com.nexgenai.controller;

import com.nexgenai.service.PythonExtractorClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Endpoint ESCO v1.2 pour l'autocomplete Angular lors de la saisie des skills d'une offre.
 *
 * GET /hr/esco/skills?q=java&limit=10
 *   → Retourne les compétences ESCO les plus proches de la requête.
 *   → Sécurisé par SecurityConfig : /hr/** requiert HR ou ADMIN.
 *   → Retourne une liste vide (jamais d'erreur) si Python enhanced non démarré.
 */
@RestController
@RequestMapping("/hr")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "http://localhost:4200")
public class EscoController {

    private final PythonExtractorClient pythonClient;

    @GetMapping("/esco/skills")
    public ResponseEntity<?> suggestEscoSkills(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit) {

        if (q == null || q.isBlank()) {
            return ResponseEntity.ok(Map.of("suggestions", List.of()));
        }

        List<Map<String, Object>> suggestions = pythonClient.suggestEscoSkills(q.trim(), limit);
        log.debug("ESCO suggest '{}' → {} résultats", q, suggestions.size());
        return ResponseEntity.ok(Map.of("suggestions", suggestions));
    }
}
