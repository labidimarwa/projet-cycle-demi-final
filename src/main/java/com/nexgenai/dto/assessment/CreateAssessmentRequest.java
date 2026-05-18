package com.nexgenai.dto.assessment;

import com.nexgenai.model.enums.AssessmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Request body for creating a new {@code Assessment} (RH or TECHNICAL).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateAssessmentRequest {

    @NotBlank
    private String name;

    private String description;

    @NotNull
    private AssessmentType type;

    private String  status;          // DRAFT | ACTIVE | ARCHIVED
    private Integer duration;        // minutes
    private Integer passingScore;
    private String  difficulty;      // EASY | MEDIUM | HARD

    /** Optional — associates the assessment with a job. */
    private String  jobId;

    /** Optional — HR owner. */
    private String  hrId;

    /** Optional — pre-assigned evaluator. */
    private String  assigneeId;
    private String  assigneeName;

    /** Optional — front-end correlation id for workflow stage pairing. */
    private String  linkId;
}
