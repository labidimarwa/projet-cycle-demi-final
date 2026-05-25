package com.nexgenai.dto.matching;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Réponse du microservice Python POST /normalize.
 * Contient la liste des compétences brutes normalisées contre ESCO v1.2.
 */
@Data
public class EscoNormalizationResult {

    private List<NormalizedSkill> normalized;

    /**
     * Résultat de normalisation pour une compétence brute.
     */
    @Data
    public static class NormalizedSkill {

        /** Terme brut tel qu'envoyé (ex : "JS", "gestion de projet"). */
        @JsonProperty("raw")
        private String raw;

        /** URI canonique ESCO (ex : "http://data.europa.eu/esco/skill/..."). Null si aucune correspondance. */
        @JsonProperty("esco_uri")
        private String escoUri;

        /** Label préféré ESCO EN (ex : "JavaScript"). Fallback = raw si escoUri null. */
        @JsonProperty("preferred_label")
        private String preferredLabel;

        /** Confiance 0.0–1.0 : 1.0 = exact, 0.8+ = fuzzy, 0.7+ = embedding, 0.0 = aucune correspondance. */
        @JsonProperty("confidence")
        private double confidence;

        /** Méthode utilisée : "exact" | "fuzzy" | "embedding" | "none". */
        @JsonProperty("method")
        private String method;

        /** Labels alternatifs ESCO (ex : ["JS", "ECMAScript"]). */
        @JsonProperty("alt_labels")
        private List<String> altLabels;
    }
}
