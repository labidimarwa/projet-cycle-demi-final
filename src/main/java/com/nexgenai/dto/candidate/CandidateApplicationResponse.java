// src/main/java/com/nexgenai/dto/candidate/CandidateApplicationResponse.java

package com.nexgenai.dto.candidate;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO returned to the candidate for each job application.
 * Includes HR-defined process mapping via stageProgress[].
 */
@Data
@Builder
public class CandidateApplicationResponse {

    // ── Job info ──────────────────────────────────────────────────────────────
    private String jobId;
    private String jobTitle;
    private String department;
    private String location;
    private String contractType;
    private String experienceLevel;

    // ── Application status ────────────────────────────────────────────────────
    /** PENDING | REVIEWED | SHORTLISTED | REJECTED | HIRED */
    private String        applicationStatus;
    private LocalDateTime appliedAt;

    // ── CV match ──────────────────────────────────────────────────────────────
    private Integer matchScore;
    private Boolean matchComputed;

    // ── Technical tests ───────────────────────────────────────────────────────
    @Builder.Default
    private List<JobTestInfo> tests = new ArrayList<>();

    // ── Process mapping (HR-defined workflow stages) ──────────────────────────
    @Builder.Default
    private List<StageProgressDTO> stageProgress = new ArrayList<>();

    // ── Nested DTO ────────────────────────────────────────────────────────────
    @Data
    @Builder
    public static class JobTestInfo {
        private String        testId;
        private String        testName;
        private String        testDescription;
        /** DRAFT | ACTIVE | ARCHIVED */
        private String        testStatus;
        private Integer       questionsCount;
        /** PENDING | IN_PROGRESS | COMPLETED */
        private String        sessionStatus;
        private Integer       score;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;

        /**
         * ✅ NOUVEAU — détermine vers quel composant Angular router :
         *   "TECHNICAL"    → /candidate/technical-test/:testId  (Problem Solving / QCM avancé)
         *   "PSYCHOMETRIC" → /candidate/test/:testId            (test psychométrique classique)
         */
        private String testCategory;
    }
}