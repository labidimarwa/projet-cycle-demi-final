package com.nexgenai.dto.assessment;

import lombok.*;

/**
 * Request body for partially updating an existing {@code Assessment}.
 * All fields are optional — only non-null values should be applied.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAssessmentRequest {

    private String  name;
    private String  description;
    private String  status;          // DRAFT | ACTIVE | ARCHIVED
    private Integer duration;
    private Integer passingScore;
    private String  difficulty;
    private String  assigneeId;
    private String  assigneeName;
    private String  workflowStageId;
}
