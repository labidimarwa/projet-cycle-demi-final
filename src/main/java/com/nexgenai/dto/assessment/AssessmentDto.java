package com.nexgenai.dto.assessment;

import com.nexgenai.model.enums.AssessmentType;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Basic response payload for the unified {@code Assessment} entity.
 *
 * <p>Replaces the previous split between {@code AssessmentResponse}-style
 * objects scattered across {@code JobResponse} and the {@code JobTestDtos}.
 * Designed to be the canonical "summary" view of an assessment, regardless
 * of whether it is an RH or a TECHNICAL one.</p>
 */

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentDto {

    private String         id;
    private String         name;
    private String         description;
    private AssessmentType type;
    private String         status;
    private Integer        duration;
    private Integer        passingScore;
    private String         difficulty;
    private LocalDate submissionDeadline;

    // Workflow link
    private String  assigneeId;
    private String  assigneeName;
    private String  linkId;
    private String  workflowStageId;

    // Relations (IDs only — full nested view is provided by detail DTOs)
    private String  jobId;
    private String  jobTitle;
    private String  hrId;

    // Lightweight aggregates
    private Integer themesCount;
    private Integer questionsCount;
    private Integer sessionsCount;

    // Timestamps
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
