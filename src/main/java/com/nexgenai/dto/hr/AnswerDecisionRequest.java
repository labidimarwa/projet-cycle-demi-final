package com.nexgenai.dto.hr;

import lombok.Data;

@Data
public class AnswerDecisionRequest {
    /** "APPROVED" or "REJECTED" */
    private String  decision;
    private String  note;
    /** Optional manual point override; null = keep auto-calculated points. */
    private Integer manualPoints;
}
