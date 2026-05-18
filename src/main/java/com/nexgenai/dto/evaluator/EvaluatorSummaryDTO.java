package com.nexgenai.dto.evaluator;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvaluatorSummaryDTO {
    private String id;
    private String fullName;
    private String email;
    private String department;
    private String title;
    private String expertiseLevel;
    private String role; // TECH_EVALUATOR | HR | ADMIN
}