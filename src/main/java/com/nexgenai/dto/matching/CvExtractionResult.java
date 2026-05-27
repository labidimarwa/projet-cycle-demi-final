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

    /** Liste brute des hard skills extraits par Qwen. */
    @JsonProperty("hard_skills")
    private List<String> hardSkills;

    /** Liste brute des soft skills extraits par Qwen. */
    @JsonProperty("soft_skills")
    private List<String> softSkills;

    /** Détections des prérequis du job effectuées par Qwen (job-aware). */
    @JsonProperty("prerequis_detectes")
    private List<PrerequisDetecte> prerequisDetectes;

    // ─── Sous-classes ─────────────────────────────────────────────────────────

    /** Une compétence extraite avec son embedding multilingue. */
    @Data
    public static class CompetenceExtraite {
        private String nom;
        /** Vecteur d'embedding multilingue (768 dims). */
        private List<Double> embedding;
        /** Score de confiance (1.0 pour Qwen). */
        private double confiance;
    }

    @Data
    public static class DiplomeExtrait {
        private String  niveau;        // Master, Licence, Bac+2…
        private String  domaine;
        private String  etablissement;
        @JsonProperty("niveauIsced")
        private Integer niveauIsced;   // ISCED level assigned by Qwen (5=Bac+2, 6=Bac+3, 7=Bac+5/Ingénieur, 8=PhD)
    }

    @Data
    public static class LangueExtraite {
        private String langue;
        private String niveau;       // Natif, Courant, Intermédiaire, Débutant
    }

    /** Résultat de la détection job-aware d'un prérequis par Qwen. */
    @Data
    public static class PrerequisDetecte {
        private String  type;     // DEGREE, EXPERIENCE, LANGUAGE, CERTIFICATION, SKILL
        private String  requis;   // valeur demandée par le job
        private String  detecte;  // valeur trouvée dans le CV (null si absent)
        private boolean present;  // true si Qwen estime que le prérequis est satisfait
    }
}
