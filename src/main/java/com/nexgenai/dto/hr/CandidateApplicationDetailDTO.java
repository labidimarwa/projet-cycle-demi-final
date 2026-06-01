package com.nexgenai.dto.hr;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Full detail returned by GET /hr/jobs/{jobId}/candidates/{candidateId}
 * Contains: profile + match details + chat transcript + voice info.
 */
@Data
@Builder
public class CandidateApplicationDetailDTO {

    // ── Candidate profile ────────────────────────────────────────────────────
    private String  candidateId;
    private String  firstName;
    private String  lastName;
    private String  email;
    private String  city;
    private String  currentPosition;
    private Integer yearsOfExperience;
    private String  educationLevel;
    private String  university;
    private String  summary;
    private String  cvDisplayName;       // original file name for download link
    private String  linkedinUrl;
    private String  githubUrl;
    private String  portfolioUrl;
    private String  remoteWorkPreference;
    private String  certifications;

    // ── AI Match result ──────────────────────────────────────────────────────
    private MatchDetailDTO match;

    // ── Application meta ─────────────────────────────────────────────────────
    private LocalDateTime appliedAt;

    // ────────────────────────────────────────────────────────────────────────
    @Data
    @Builder
    public static class MatchDetailDTO {
        private Integer score;
        private String  verdict;
        private String  resume;
        private String  skillsMatched;   // raw JSON string — parsed on frontend
        private String  skillsMissing;   // raw JSON string
        private String  dimensionsJson;  // raw JSON string
        private LocalDateTime computedAt;
    }

}