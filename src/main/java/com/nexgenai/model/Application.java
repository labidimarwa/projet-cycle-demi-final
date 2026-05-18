package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Tracks a candidate's application to a job.
 * Created when a candidate calls POST /candidate/apply.
 */
@Entity
@Table(name = "applications",
       uniqueConstraints = @UniqueConstraint(columnNames = {"candidate_id", "job_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "candidate_id", nullable = false)
    private String candidateId;

    @Column(name = "job_id", nullable = false)
    private String jobId;

    /** Original CV filename uploaded with application (may differ from profile CV) */
    @Column(name = "cv_path")
    private String cvPath;

    /** Voice transcript submitted with application */
    @Column(name = "voice_transcript", columnDefinition = "TEXT")
    private String voiceTranscript;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private ApplicationStatus status = ApplicationStatus.PENDING;

    @Column(name = "applied_at", nullable = false)
    private LocalDateTime appliedAt;

    @PrePersist
    protected void onCreate() {
        if (appliedAt == null) appliedAt = LocalDateTime.now();
        if (status    == null) status    = ApplicationStatus.PENDING;
    }

    public enum ApplicationStatus {
        PENDING, REVIEWED, SHORTLISTED, REJECTED, HIRED
    }
}