package com.nexgenai.dto.matching;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

/**
 * Mappe exactement la réponse JSON de POST /extract du microservice Python.
 * Contient le texte brut + entités extraites + embeddings JobBERTa.
 */
@Data
public class CvExtractionResult {

    @JsonProperty("texte_brut")
    private String texteBrut;

    /** Compétences avec leurs embeddings calculés par Python (JobBERTa). */
    @JsonProperty("competences")
    private List<CompetenceExtraite> competences;

    /** Durée d'expérience estimée en mois. */
    @JsonProperty("experience_mois")
    private int experienceMois;

    @JsonProperty("diplomes")
    private List<DiplomeExtrait> diplomes;

    @JsonProperty("langues")
    private List<LangueExtraite> langues;

    @JsonProperty("certifications")
    private List<String> certifications;

    @JsonProperty("postes")
    private List<String> postes;

    @JsonProperty("secteurs")
    private List<String> secteurs;

    // ─── Sous-classes ─────────────────────────────────────────────────────────

    /** Une compétence extraite avec son embedding JobBERTa. */
    @Data
    public static class CompetenceExtraite {
        private String nom;
        /** Vecteur d'embedding (dimension 768 pour JobBERTa). */
        private List<Double> embedding;
        /** Score de confiance du NER (0.0 – 1.0). */
        private double confiance;
    }

    @Data
    public static class DiplomeExtrait {
        private String niveau;       // Master, Licence, Bac+2…
        private String domaine;
        private String etablissement;
    }

    @Data
    public static class LangueExtraite {
        private String langue;
        private String niveau;       // Natif, Courant, Intermédiaire, Débutant
    }
}
