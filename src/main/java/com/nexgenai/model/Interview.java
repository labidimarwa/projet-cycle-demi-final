// Interview.java
package com.nexgenai.model;

import com.nexgenai.model.enums.StageType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "interviews")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Interview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "job_id", nullable = false)
    private String jobId;

    @Column(name = "job_title")
    private String jobTitle;

    @Column(name = "job_department")
    private String jobDepartment;

    @Column(name = "workflow_stage_id", nullable = false)
    private String workflowStageId;

    @Column(name = "stage_name")
    private String stageName;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage_type")
    private StageType stageType;

    /** Primary assignee (kept for backward compat — first in the list) */
    @Column(name = "assignee_id")
    private String assigneeId;

    @Column(name = "assignee_name")
    private String assigneeName;

    // ── Schedule configuration ────────────────────────────────────────────────

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /**
     * Working day start time stored as "HH:mm".
     * Default: "09:00"
     */
    @Column(name = "day_start_time", length = 10)
    private String dayStartTime;

    /**
     * Working day end time stored as "HH:mm".
     * Default: "18:00"
     */
    @Column(name = "day_end_time", length = 10)
    private String dayEndTime;

    /** Duration of each interview slot in minutes */
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "interviews_per_day")
    private Integer interviewsPerDay;

    /**
     * JSON array of excluded time ranges.
     * Each entry is "HH:mm-HH:mm", e.g. ["12:00-13:00", "16:00-16:30"]
     */
    @Column(name = "excluded_hours_json", columnDefinition = "TEXT")
    private String excludedHoursJson;

    /**
     * JSON array of assignee IDs (HR / evaluators / admins).
     * e.g. ["uuid1", "uuid2", "uuid3"]
     */
    @Column(name = "assignee_ids_json", columnDefinition = "TEXT")
    private String assigneeIdsJson;

    // ── Evaluation grid ───────────────────────────────────────────────────────

    /** JSON array of EvaluationCriteria */
    @Column(name = "evaluation_grid_json", columnDefinition = "TEXT")
    private String evaluationGridJson;

    @Column(name = "grid_configured")
    private Boolean gridConfigured = false;

    @Column(name = "schedule_configured")
    private Boolean scheduleConfigured = false;

    @Column(name = "slots_generated")
    private Boolean slotsGenerated = false;

    /**
     * Phase lifecycle: NOT_CONFIGURED | CONFIGURED | IN_PROGRESS | CLOSED
     * - CLOSED means this phase is done and the next phase can be configured
     */
    @Column(name = "phase_status", length = 30)
    private String phaseStatus = "NOT_CONFIGURED";

    /**
     * Computed end date/time of this interview phase (last slot end).
     * Set automatically when slots are generated.
     */
    @Column(name = "computed_end_date_time")
    private LocalDateTime computedEndDateTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (gridConfigured    == null) gridConfigured    = false;
        if (scheduleConfigured == null) scheduleConfigured = false;
        if (slotsGenerated    == null) slotsGenerated    = false;
        if (dayStartTime      == null) dayStartTime      = "09:00";
        if (dayEndTime        == null) dayEndTime        = "18:00";
        if (phaseStatus       == null) phaseStatus       = "NOT_CONFIGURED";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}