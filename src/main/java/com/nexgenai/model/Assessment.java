package com.nexgenai.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nexgenai.model.enums.AssessmentType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Unified Assessment entity.
 *
 * Merges the previous {@code Assessment} (workflow-level metadata) and
 * {@code JobTest} (full test definition with themes / questions) entities
 * into a single model. The {@link AssessmentType} field distinguishes
 * between RH (psychometric) and TECHNICAL assessments.
 *
 * Phase 1 of the backend refactoring – core models.
 */
@Entity
@Table(name = "assessments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(exclude = {"job", "hr", "themes", "questions", "sessions"})
public class Assessment {

    // ── Identity ──────────────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Distinguishes RH (psychometric) from TECHNICAL assessments. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssessmentType type;

    /** Lifecycle status of the assessment. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private AssessmentStatus status = AssessmentStatus.DRAFT;

    // ── Configuration ─────────────────────────────────────────────────────────

    /** Total allowed duration in minutes (0 / null = no limit). */
    private Integer duration;

    /** Minimum percentage required to pass (0–100). */
    private Integer passingScore;

    /** Optional difficulty hint: EASY | MEDIUM | HARD. */
    @Column(length = 20)
    private String difficulty;

    // ── Workflow integration ──────────────────────────────────────────────────

    /** The assigned evaluator's user ID. */
    @Column(name = "assignee_id")
    private String assigneeId;

    /** Denormalised display name of the assignee. */
    @Column(name = "assignee_name")
    private String assigneeName;

    /**
     * Client-side link ID used to pair this assessment with its auto-generated
     * {@link WorkflowStage}. Stored for reference.
     */
    @Column(name = "link_id")
    private String linkId;

    /** Optional back-reference to the workflow stage that owns this assessment. */
    @Column(name = "workflow_stage_id")
    private String workflowStageId;

    
    @Column(name = "submission_deadline")
    private LocalDate submissionDeadline;
    // ── Relationships ─────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id")
    @JsonIgnore
    private Job job;

    /** HR that created / owns the assessment (nullable for legacy data). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hr_id")
    @JsonIgnore
    private HR hr;

    /**
     * Test themes (used primarily by RH psychometric assessments).
     */
    @OneToMany(mappedBy = "assessment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    @Builder.Default
    private Set<TestTheme> themes = new LinkedHashSet<>();

    /**
     * Direct questions attached to the assessment (used primarily by
     * TECHNICAL assessments — QCM + PROBLEM_SOLVING).
     */
    @OneToMany(mappedBy = "assessment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    @Builder.Default
    private Set<Question> questions = new LinkedHashSet<>();

    /**
     * Candidate sessions started against this assessment.
     */
    @OneToMany(mappedBy = "assessment", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    @Builder.Default
    private Set<TestSession> sessions = new LinkedHashSet<>();

    // ── Timestamps ────────────────────────────────────────────────────────────

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = AssessmentStatus.DRAFT;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Convenience helpers ───────────────────────────────────────────────────

    public Set<TestTheme> getThemes() {
        if (themes == null) themes = new LinkedHashSet<>();
        return themes;
    }

    public Set<Question> getQuestions() {
        if (questions == null) questions = new LinkedHashSet<>();
        return questions;
    }

    public Set<TestSession> getSessions() {
        if (sessions == null) sessions = new LinkedHashSet<>();
        return sessions;
    }

    public void addTheme(TestTheme theme) {
        getThemes().add(theme);
        theme.setAssessment(this);
    }

    public void addQuestion(Question question) {
        getQuestions().add(question);
        question.setAssessment(this);
    }

    public void addSession(TestSession session) {
        getSessions().add(session);
        session.setAssessment(this);
    }

    // ── Nested types ──────────────────────────────────────────────────────────

    /** Lifecycle status. Mirrors the legacy {@code JobTest.TestStatus}. */
    public enum AssessmentStatus {
        DRAFT, ACTIVE, ARCHIVED
    }
}
