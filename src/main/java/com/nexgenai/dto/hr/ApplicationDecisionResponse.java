// src/main/java/com/nexgenai/dto/hr/ApplicationDecisionResponse.java
package com.nexgenai.dto.hr;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ApplicationDecisionResponse {
    private String candidateId;
    private String jobId;
    private String decision;
    private String nextStageName;   // null if rejected or no next stage
    private String message;
}