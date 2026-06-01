// InterviewDtos.java
package com.nexgenai.dto.interview;

import com.nexgenai.model.enums.StageType;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class InterviewDtos {

    private InterviewDtos() {}

    // ── Summary returned to frontend ──────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class InterviewSummaryResponse {
        private String    id;
        private String    jobId;
        private String    jobTitle;
        private String    jobDepartment;
        private String    workflowStageId;
        private String    stageName;
        private StageType stageType;
        private String    assigneeId;
        private String    assigneeName;
        private LocalDate startDate;
        private LocalDate endDate;
        private Integer   durationMinutes;
        private Integer   interviewsPerDay;
        private Boolean   gridConfigured;
        private Boolean   scheduleConfigured;
        private Boolean   slotsGenerated;
        private int       totalCandidates;
        private int       completedInterviews;

        /** Excluded time ranges: e.g. ["12:00-13:00", "08:00-09:00"] */
        private List<String> excludedHours;

        /** Working day start time, e.g. "09:00" */
        private String dayStartTime;

        /** Working day end time, e.g. "18:00" */
        private String dayEndTime;

        /** All assignees selected for this interview stage */
        private List<AssigneeDTO> assignees;

        /**
         * Phase lifecycle: NOT_CONFIGURED | CONFIGURED | IN_PROGRESS | CLOSED
         */
        private String phaseStatus;

        /** Computed end datetime of this phase (last slot end). */
        private LocalDateTime computedEndDateTime;

        /** Number of candidates eligible for this phase. */
        private int eligibleCandidates;
    }

    // ── Configuration request from frontend ───────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class InterviewConfigRequest {
        private LocalDate    startDate;
        private LocalDate    endDate;

        /** e.g. "09:00" */
        private String       dayStartTime;

        /** e.g. "18:00" */
        private String       dayEndTime;

        private Integer      durationMinutes;
        private Integer      interviewsPerDay;

        /**
         * Excluded time ranges sent as "HH:mm-HH:mm" strings.
         * e.g. ["12:00-13:00", "16:00-16:30"]
         */
        private List<String> excludedHours;

        /** IDs of selected HR / evaluator / admin users */
        private List<String> assigneeIds;

        private List<EvaluationCriteriaDto> evaluationGrid;
    }

    // ── Evaluation criteria ───────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EvaluationCriteriaDto {
        private String  id;
        private String  name;
        private String  description;
        private String  scoringType; // RATING_5 | RATING_10 | YES_NO | TEXT
        private Integer weight;
        private boolean required;
    }

    // ── Slot response ─────────────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SlotResponse {
        private String  id;
        private String  candidateId;
        private String  candidateName;
        private String  candidateEmail;
        private String  assigneeId;
        private String  assigneeName;
        private String  slotStart;
        private String  slotEnd;
        private String  status;
        private Integer overallScore;
        private String  decision;
        private boolean evaluated;
    }

    // ── Evaluation submit request ─────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EvaluationSubmitRequest {
        private List<CriteriaScore> scores;
        private String  comment;
        private String  decision;      // ACCEPTED | REJECTED
        private Integer overallScore;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CriteriaScore {
        private String criteriaId;
        private String criteriaName;
        private Object value; // Integer, Boolean or String depending on type
    }

    // ── Assignee DTO (used in summary response) ───────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AssigneeDTO {
        private String id;
        private String fullName;
        private String email;
        private String role; // HR | TECH_EVALUATOR | ADMIN
    }

    // ── Phase status overview for a job ───────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class JobPhasesStatusResponse {
        private String jobId;
        private String jobTitle;

        private InterviewSummaryResponse rhInterview;
        private InterviewSummaryResponse technicalInterview;
        private InterviewSummaryResponse adminInterview;

        /** True when RH is CLOSED → Technical can be configured */
        private boolean canConfigureTechnical;
        /** True when both RH and Technical are CLOSED → Admin can be configured */
        private boolean canConfigureAdmin;

        /** Earliest possible start for Technical (day after RH computed end, or today) */
        private LocalDate earliestTechnicalStart;
        /** Earliest possible start for Admin (day after Technical computed end, or today) */
        private LocalDate earliestAdminStart;

        /** Dates blocked for Technical (dates occupied by RH slots) */
        private List<LocalDate> rhOccupiedDates;
        /** Dates blocked for Admin (dates occupied by RH or Technical slots) */
        private List<LocalDate> technicalOccupiedDates;
    }

    // ── Schedule suggestion ───────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ScheduleSuggestionResponse {
        private int         candidateCount;
        private int         assigneeCount;
        private int         durationMinutes;
        private int         roundsPerDay;          // time slots available per day
        private int         totalRoundsNeeded;     // ceil(candidateCount / assigneeCount)
        private int         estimatedDaysNeeded;
        private LocalDate   suggestedStartDate;
        private LocalDate   suggestedEndDate;
        private List<LocalDate> blockedDates;      // dates that cannot be used (from previous phase)
    }

    // ── Close phase request ───────────────────────────────────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ClosePhaseRequest {
        /** Optional reason / comment for closing the phase */
        private String reason;
    }

    // ── Candidate-facing slot view (no evaluation details) ────────────────────
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CandidateSlotView {
        private String    id;
        private String    jobId;
        private String    jobTitle;
        private String    stageName;
        private StageType stageType;
        /** ISO datetime string */
        private String    slotStart;
        private String    slotEnd;
        /** SCHEDULED | COMPLETED | CANCELLED | NO_SHOW */
        private String    status;
        /** PENDING | ACCEPTED | REJECTED */
        private String    decision;
        /** Name of the interviewer */
        private String    assigneeName;
    }
}
