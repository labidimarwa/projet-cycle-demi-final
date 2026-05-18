
package com.nexgenai.dto.technicaltest;
 
import lombok.*;
import java.util.List;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SimpleQuestionDto {
    private String               id;
    private String               title;
    private String               statement;
    private int                  points;
    private int                  orderIndex;
    private String               type;            // PROBLEM_SOLVING | QCM
    // PROBLEM_SOLVING
    private String               complexity;
    private Double               timeLimit;
    private Integer              memoryLimit;
    private List<TestCaseDto>    testCases;
    private List<String>         supportedLangs;
    private String               selectedLanguage;
    // QCM
    private String               questionType;    // RADIO|CHECKBOX|LIKERT|RANKING
    private List<QcmOptionDto>   options;
    private List<Integer>        likertPoints;
    private String               imageUrl;
    // Saved state
    private Object               savedAnswer;
}