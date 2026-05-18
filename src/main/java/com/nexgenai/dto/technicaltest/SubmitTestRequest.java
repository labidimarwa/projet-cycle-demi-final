// src/main/java/com/nexgenai/dto/technicaltest/TechnicalTestDtos.java
 
package com.nexgenai.dto.technicaltest;
 
import lombok.*;
import java.util.List;
import java.util.Map;
 

// -- SubmitTestRequest.java --
@Data @NoArgsConstructor @AllArgsConstructor
public class SubmitTestRequest {
    private String                   sessionId;
    private Map<String, Object>      answers;
    private List<AntiCheatEventDto>  antiCheatLog;
    private int                      durationSeconds;
}