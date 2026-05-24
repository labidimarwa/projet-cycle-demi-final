package com.nexgenai.dto.matching;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Réponse de POST /embed : map { "Java" → [0.12, -0.04, …], … }.
 * Spring Boot utilise ces vecteurs pour la similarité cosinus.
 */
@Data
public class EmbedResult {
    /** Clé = nom du skill, Valeur = vecteur d'embedding JobBERTa. */
    private Map<String, List<Double>> embeddings;
}
