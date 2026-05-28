package com.nexgenai.dto.matching;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

/**
 * Mappe la réponse JSON de POST /extract (pipeline job-driven sémantique).
 * Python calcule tous les scores (MiniLM + Qwen RAG) ; Java applique
 * uniquement les pondérations et les règles métier.
 */
@Data
public class CvExtractionResult {

    @JsonProperty("texte_brut")
    private String texteBrut;

    /** Score MiniLM de chaque skill du job contre les paragraphes du CV. */
    @JsonProperty("skills_evalues")
    private List<SkillEvalue> skillsEvalues;

    /** Évaluation sémantique (RAG + Qwen) de chaque prérequis du job. */
    @JsonProperty("prerequis_evalues")
    private List<PrerequisEvalue> prerequisEvalues;

    // ─── Sous-classes ─────────────────────────────────────────────────────────

    @Data
    public static class SkillEvalue {
        /** Nom de la compétence (tel que défini dans le job). */
        private String  nom;
        /** TECHNICAL ou SOFT. */
        private String  type;
        /** Similarité cosinus MiniLM entre le nom du skill et le meilleur paragraphe CV (0–1). */
        private double  score;
        /** true si score >= 0.55. */
        private boolean present;
    }

    @Data
    public static class PrerequisEvalue {
        /** DEGREE | EXPERIENCE | LANGUAGE | CERTIFICATION | SKILL */
        @JsonProperty("type")    private String  type;
        /** Valeur demandée par le job (reprise de la définition RH). */
        @JsonProperty("requis")  private String  requis;
        /** Ce que Qwen a trouvé dans le CV (null si absent). */
        @JsonProperty("detecte") private String  detecte;
        /** true si Qwen estime que le prérequis est satisfait. */
        @JsonProperty("present") private boolean present;
        /** Score sémantique 0–1 attribué par Qwen après analyse RAG. */
        @JsonProperty("score")   private double  score;
    }
}
