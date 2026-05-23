package com.nexgenai.dto.hr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AnswerDecisionResponse {
    private String  questionId;
    private String  decision;      // APPROVED | REJECTED | null
    private String  note;
    private Integer manualPoints;
}
