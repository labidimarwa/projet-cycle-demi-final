package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "job_matches",
       uniqueConstraints = @UniqueConstraint(columnNames = {"candidate_id", "job_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // Candidat concerné
    @Column(name = "candidate_id", nullable = false)
    private String candidateId;

    // Job concerné
    @Column(name = "job_id", nullable = false)
    private String jobId;

    // Score global 0-100
    @Column(name = "score", nullable = false)
    private Integer score;

    // Verdict court (ex: "Très bon profil")
    @Column(name = "verdict", length = 200)
    private String verdict;

    // Résumé de l'analyse
    @Column(name = "resume", columnDefinition = "TEXT")
    private String resume;

    // Compétences matchées (JSON array string)
    @Column(name = "skills_matched", columnDefinition = "TEXT")
    private String skillsMatched;

    // Compétences manquantes (JSON array string)
    @Column(name = "skills_missing", columnDefinition = "TEXT")
    private String skillsMissing;

    // Détails dimensions (JSON)
    @Column(name = "dimensions_json", columnDefinition = "TEXT")
    private String dimensionsJson;

    // Quand le calcul a été fait
    @Column(name = "computed_at")
    private LocalDateTime computedAt;

    // Hash du CV pour détecter les changements
    @Column(name = "cv_hash", length = 64)
    private String cvHash;
}