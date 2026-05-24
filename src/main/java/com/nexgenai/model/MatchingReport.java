package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * Entité JPA — rapport de matching CV ↔ Offre sauvegardé en BDD.
 * Mis à jour à chaque recalcul si le CV a changé (cvHash différent).
 */
@Entity
@Table(name = "matching_reports", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"job_id", "candidate_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchingReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "job_id", nullable = false)
    private String jobId;

    @Column(name = "candidate_id", nullable = false)
    private String candidateId;

    @Column(name = "score_global")
    private double scoreGlobal;

    /** RETENIR | A_ETUDIER | REJETER */
    @Column(name = "recommendation", length = 20)
    private String recommendation;

    @Column(name = "force_rejet")
    private boolean forceRejet;

    @Column(name = "force_rejet_raison", columnDefinition = "TEXT")
    private String forceRejetRaison;

    /** JSON sérialisé de List<SkillMatchResult>. */
    @Column(name = "skills_json", columnDefinition = "TEXT")
    private String skillsJson;

    /** JSON sérialisé de List<PrerequisiteMatchResult>. */
    @Column(name = "prerequisite_json", columnDefinition = "TEXT")
    private String prerequisiteJson;

    /** JSON sérialisé de List<String> (points forts Mistral). */
    @Column(name = "points_forts", columnDefinition = "TEXT")
    private String pointsForts;

    /** JSON sérialisé de List<String> (points faibles Mistral). */
    @Column(name = "points_faibles", columnDefinition = "TEXT")
    private String pointsFaibles;

    @Column(name = "analyse_texte", columnDefinition = "TEXT")
    private String analyseTexte;

    @Column(name = "computed_at")
    private LocalDateTime computedAt;

    /** MD5 du contenu binaire du CV — permet de détecter un changement de CV. */
    @Column(name = "cv_hash", length = 64)
    private String cvHash;
}
