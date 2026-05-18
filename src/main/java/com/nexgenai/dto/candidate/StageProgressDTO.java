package com.nexgenai.dto.candidate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One stage row in the HR-defined process mapping.
 * Embedded inside CandidateApplicationResponse.stageProgress[].
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StageProgressDTO {
    private String id;
    private int    stageOrder;
    private String stageName;
    private String stageType;    // AI_SCREENING | TECHNICAL_TEST | RH_TEST | RH_INTERVIEW | TECHNICAL_INTERVIEW | ADMIN_INTERVIEW
    private String status;       // PENDING | IN_PROGRESS | COMPLETED | SKIPPED
    private String startedAt;    // ISO LocalDateTime string, null if not started
    private String completedAt;  // ISO LocalDateTime string, null if not done
    private String hrNote;       // optional annotation by HR
}