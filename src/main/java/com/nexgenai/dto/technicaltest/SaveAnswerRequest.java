
package com.nexgenai.dto.technicaltest;
 
import lombok.*;
import java.util.List;
import java.util.Map;

 
// -- SaveAnswerRequest.java --
@Data @NoArgsConstructor @AllArgsConstructor
public class SaveAnswerRequest {
    private String questionId;
    private Object answer;    // code+lang map OR qcm optionId(s)/likert value
}