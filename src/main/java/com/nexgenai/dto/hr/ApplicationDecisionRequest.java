// src/main/java/com/nexgenai/dto/hr/ApplicationDecisionRequest.java
package com.nexgenai.dto.hr;

import lombok.Data;

@Data
public class ApplicationDecisionRequest {
    /** "ACCEPTED" or "REJECTED" */
    private String decision;
    /** Optional HR note shown to candidate */
    private String note;
}