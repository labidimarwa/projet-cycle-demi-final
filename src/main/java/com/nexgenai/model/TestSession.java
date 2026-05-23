package com.nexgenai.model;

import com.nexgenai.model.enums.AssessmentType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Unified candidate test session.
 *
 * Merges the previous {@code TestSession} (psychometric / RH) and
 * {@code TechnicalSession} (technical: QCM + problem solving) entities into
 * a single model. The {@code type} field — copied from the parent
 * {@link Assessment} — distinguishes the two flavours and exposes both the
 * RH scoring fields and the technical-test anti-cheat / timing fields.
 *
 * Phase 1 of the backend refactoring – core models.
 */
@Entity
@Table(
    name = "test_sessions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_session_candidate_assessment",
        columnNames = {"candidate_id", "assessment_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(exclude = {"candidate", "assessment"})
public class TestSession {

    // ── Identity ──────────────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Mirrors the parent assessment's type for fast filtering. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssessmentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SessionStatus status = SessionStatus.PENDING;

    // ── Relationships ─────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id", nullable = false)
    private Candidate candidate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", nullable = false)
    private Assessment assessment;

    // ── Timing ────────────────────────────────────────────────────────────────

    /** Hard time-limit (seconds). 0 / null = no limit. */
    @Column(name = "time_limit_seconds")
    private Integer timeLimitSeconds;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    // ── Scoring ───────────────────────────────────────────────────────────────

    /** Final percentage score 0–100. */
    private Integer score;

    @Column(name = "earned_points")
    private Integer earnedPoints;

    @Column(name = "total_points")
    private Integer totalPoints;

    // ── Answers (serialized JSON) ─────────────────────────────────────────────

    /**
     * Candidate answers serialised as JSON. Format depends on session type:
     * <ul>
     *   <li>RH: {@code { questionId: optionId | [optionIds] | likertValue }}</li>
     *   <li>TECHNICAL: {@code { questionId: { code, language } | optionId | ... }}</li>
     * </ul>
     */
    @Column(name = "answers_json", columnDefinition = "TEXT")
    @Builder.Default
    private String answersJson = "{}";

    /**
     * Evaluator per-answer decisions: Map&lt;questionId, AnswerDecisionData&gt;.
     * Each entry holds decision (APPROVED/REJECTED), optional note, optional manual point override.
     */
    @Column(name = "decisions_json", columnDefinition = "TEXT")
    @Builder.Default
    private String decisionsJson = "{}";

    // ── Anti-Cheat ────────────────────────────────────────────────────────────

    /** JSON array of granular anti-cheat events. */
    @Column(name = "anti_cheat_json", columnDefinition = "TEXT")
    @Builder.Default
    private String antiCheatJson = "[]";

    /** Aggregated counters – exposed as columns for quick reporting / sorting. */
    @Column(name = "tab_switches")
    @Builder.Default
    private Integer tabSwitches = 0;

    @Column(name = "copy_paste_count")
    @Builder.Default
    private Integer copyPasteCount = 0;

    @Column(name = "window_blur_count")
    @Builder.Default
    private Integer windowBlurCount = 0;

    @Column(name = "devtools_open_count")
    @Builder.Default
    private Integer devtoolsOpenCount = 0;

    @Column(name = "right_click_count")
    @Builder.Default
    private Integer rightClickCount = 0;

    /** Computed risk level: LOW | MEDIUM | HIGH. */
    @Column(name = "risk_level", length = 10)
    private String riskLevel;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) status = SessionStatus.PENDING;
    }

    // ── Nested types ──────────────────────────────────────────────────────────

    /**
     * Unified session lifecycle. Combines the legacy {@code TestSession} and
     * {@code TechnicalSession} states.
     */
    public enum SessionStatus {
        /** Assigned but not yet opened by the candidate. */
        PENDING,
        /** Same as {@link #PENDING}; kept for technical-test API compatibility. */
        NOT_STARTED,
        /** Candidate is currently taking the test. */
        IN_PROGRESS,
        /** Successfully submitted by the candidate. */
        COMPLETED,
        /** Time limit elapsed before the candidate submitted. */
        EXPIRED
    }
}
