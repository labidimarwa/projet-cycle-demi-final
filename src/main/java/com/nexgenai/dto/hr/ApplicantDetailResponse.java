package com.nexgenai.dto.hr;

import com.nexgenai.dto.candidate.StageProgressDTO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Full detail for one candidate on one job.
 * Returned by GET /hr/jobs/{jobId}/applicants/{candidateId}
 */
@Data
@Builder
public class ApplicantDetailResponse {

    // ── Candidate info ────────────────────────────────────────────────────────
    private String candidateId;
    private String firstName;
    private String lastName;
    private String email;
    private String linkedinUrl;
    private String githubUrl;
    private String portfolioUrl;
    private boolean hasCv;
    private String cvPath;

    // ── Application ───────────────────────────────────────────────────────────
    private String applicationStatus;
    private String appliedAt;

    // ── AI Match (CV) ─────────────────────────────────────────────────────────
    private Integer matchScore;
    private Boolean matchComputed;
    /** 6 sub-dimensions from the AI scoring */
    private List<DimensionScore> dimensions;
    private List<String> skillsMatched;
    private List<String> skillsMissing;

    // ── Process mapping stages ────────────────────────────────────────────────
    private List<StageProgressDTO> stageProgress;

    // ── Nested types ──────────────────────────────────────────────────────────

    @Data
    @Builder
    public static class DimensionScore {
        private String  name;
        private Integer score;
    }
}