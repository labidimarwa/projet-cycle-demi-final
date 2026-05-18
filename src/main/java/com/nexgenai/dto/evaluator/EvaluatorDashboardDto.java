package com.nexgenai.dto.evaluator;

import lombok.*;
import java.util.List;

/**
 * Top-level response for GET /evaluator/dashboard
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class EvaluatorDashboardDto {

    // ── KPI counters ──────────────────────────────────────────────────────────
    private int     todayInterviewsCount;
    private String  nextInterviewTime;       // "HH:mm" or null
    private int     assignedTestsCount;
    private int     activeTestsCount;
    private int     draftTestsCount;
    private int     pendingReviewsCount;     // test submissions not yet reviewed
    private int     completedThisWeek;
    private int     acceptedThisWeek;
    private int     rejectedThisWeek;

    // ── Today's interview slots ───────────────────────────────────────────────
    private List<SlotDto> todaySlots;

    // ── Upcoming slots (next 7 days, excluding today) ─────────────────────────
    private List<SlotDto> upcomingSlots;

    // ── Recently completed slots ──────────────────────────────────────────────
    private List<SlotDto> recentCompleted;

    // ── Assigned technical tests ──────────────────────────────────────────────
    private List<AssignedTestDto> assignedTests;

    // ── Pipeline counts ───────────────────────────────────────────────────────
    private PipelineDto pipeline;

    // =========================================================================
    // Nested DTOs
    // =========================================================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SlotDto {
        private String  slotId;
        private String  candidateId;
        private String  candidateName;
        private String  candidateEmail;
        private String  candidateAvatar;
        private String  jobTitle;
        private String  stageName;
        private String  stageType;
        private String  slotStart;          // ISO-8601
        private String  slotEnd;
        private int     durationMinutes;
        private String  status;             // SCHEDULED | COMPLETED
        private String  decision;           // PENDING | ACCEPTED | REJECTED
        private Integer overallScore;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AssignedTestDto {
        private String  testId;
        private String  testName;
        private String  jobId;
        private String  jobTitle;
        private String  department;
        private String  status;             // ACTIVE | DRAFT | ARCHIVED
        private int     themesCount;
        private int     questionsCount;
        private int     candidatesCount;
        private int     completedCount;
        private int     completionRate;     // 0-100
        private String  stageType;         // TECHNICAL_TEST
        private String  createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PipelineDto {
        private int awaitingInterview;      // SCHEDULED slots for this evaluator
        private int testInProgress;         // TechnicalSession IN_PROGRESS
        private int pendingReview;          // TechnicalSession COMPLETED but decision PENDING
        private int accepted;
        private int rejected;
    }
}