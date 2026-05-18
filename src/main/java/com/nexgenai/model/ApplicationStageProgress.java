// ══════════════════════════════════════════════════════════════════════════════
// FILE: src/main/java/com/nexgenai/model/ApplicationStageProgress.java  (NEW)
// ══════════════════════════════════════════════════════════════════════════════
// Tracks which stage a candidate is at for a given job application.
// One row per (candidate, job, workflowStage) triplet.
// ══════════════════════════════════════════════════════════════════════════════
package com.nexgenai.model;

import com.nexgenai.model.enums.StageProgressStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "application_stage_progress",
       uniqueConstraints = @UniqueConstraint(columnNames = {"candidate_id","job_id","stage_order"}))
public class ApplicationStageProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "candidate_id", nullable = false)
    private String candidateId;

    @Column(name = "job_id", nullable = false)
    private String jobId;

    /** Mirror of WorkflowStage.stageOrder — used to order display */
    @Column(name = "stage_order", nullable = false)
    private Integer stageOrder;

    /** Mirror of WorkflowStage.name for display */
    @Column(name = "stage_name")
    private String stageName;

    /** Mirror of WorkflowStage.stageType */
    @Column(name = "stage_type")
    private String stageType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StageProgressStatus status = StageProgressStatus.PENDING;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Optional note by HR (e.g. "Passed technical interview") */
    @Column(name = "hr_note", columnDefinition = "TEXT")
    private String hrNote;
}