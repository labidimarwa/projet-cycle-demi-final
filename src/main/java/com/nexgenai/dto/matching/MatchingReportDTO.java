package com.nexgenai.dto.matching;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Rapport complet de matching CV ↔ Offre.
 * Renvoyé à Angular pour affichage dans MatchingReportComponent.
 */
@Data
@Builder
public class MatchingReportDTO {

    private String id;
    private String jobId;
    private String jobTitre;
    private String candidateId;
    private String candidatNom;

    // ─── Score global ─────────────────────────────────────────────────────────
    /** Score global pondéré (0 – 100). */
    private double scoreGlobal;

    /** RETENIR (≥75) | A_ETUDIER (≥50) | REJETER (<50 ou rejet forcé). */
    private String recommendation;

    /** true si un skill/prérequis obligatoire n'est pas satisfait → score = 0. */
    private boolean forceRejet;

    /** Explication du rejet forcé (ex : "Compétence obligatoire manquante : Java"). */
    private String forceRejetRaison;

    // ─── Détail compétences ───────────────────────────────────────────────────
    private List<SkillMatchResult> skills;

    /** Score pondéré de l'ensemble des skills (section skills du global). */
    private double scoreSkills;

    /** Score pondéré des compétences techniques uniquement. */
    private double scoreSkillsTechnique;

    /** Score pondéré des soft skills uniquement. */
    private double scoreSkillsSoft;

    // ─── Détail prérequis ─────────────────────────────────────────────────────
    private List<PrerequisiteMatchResult> prerequis;

    /** Score moyen des prérequis. */
    private double scorePrerequisite;

    private LocalDateTime computedAt;
}
