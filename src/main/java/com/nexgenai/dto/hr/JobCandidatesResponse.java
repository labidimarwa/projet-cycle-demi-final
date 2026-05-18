package com.nexgenai.dto.hr;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

// ────────────────────────────────────────────────────────────────────────────
//  Top-level wrapper returned by GET /hr/jobs/{jobId}/candidates
// ────────────────────────────────────────────────────────────────────────────
@Data
@Builder
public class JobCandidatesResponse {

    private String  jobId;
    private String  jobTitle;
    private int     totalApplicants;
    private double  avgMatchScore;
    private List<CandidateSummaryDTO> candidates;

    // ── One row in the candidates table ─────────────────────────────────────
    @Data
    @Builder
    public static class CandidateSummaryDTO {
        private String  candidateId;
        private String  firstName;
        private String  lastName;
        private String  email;
        private String  currentPosition;
        private Integer yearsOfExperience;
        private String  city;

        // Match
        private Integer matchScore;      // 0-100
        private String  matchVerdict;

        // Chat interview
        private Boolean chatDone;
        private Integer chatScore;       // interview score 0-100
        private Integer chatQuestions;   // how many questions answered

        // CV
        private Boolean hasCv;
        private String  cvDisplayName;

        // Application date
        private LocalDateTime appliedAt;
    }
}