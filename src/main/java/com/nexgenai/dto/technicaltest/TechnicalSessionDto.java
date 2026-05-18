 
package com.nexgenai.dto.technicaltest;
 
import lombok.*;
import java.util.List;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TechnicalSessionDto {
    private String                  sessionId;
    private String                  testId;
    private String                  testName;
    private String                  jobTitle;
    private int                     timeLimitSeconds;
    private List<SimpleQuestionDto> questions;
}
 