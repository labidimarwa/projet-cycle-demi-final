package com.nexgenai.dto.hr;

import lombok.Builder;
import lombok.Data;

/**
 * Lightweight DTO returned in the applicants list for a job.
 * One row per candidate in GET /hr/jobs/{jobId}/applicants
 */
@Data
@Builder
public class ApplicantSummaryResponse {
    private String  candidateId;
    private String  firstName;
    private String  lastName;
    private String  email;
    // Application
    private String  applicationStatus;   // PENDING | REVIEWED | SHORTLISTED | REJECTED | HIRED
    private String  appliedAt;           // ISO string
    // AI scores
    private Integer matchScore;          // CV match 0–100, null if not computed
    private Boolean matchComputed;
    // Current stage
    private String  currentStageName;    // name of the IN_PROGRESS stage, or null
    private Integer stageProgress;       // % completed (0–100)
    // Links
    private boolean hasCv;
}