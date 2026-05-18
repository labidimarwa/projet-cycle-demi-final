package com.nexgenai.dto.technicaltest;
 
import lombok.*;
import java.util.List;
import java.util.Map;
 
// ══════════════════════════════════════════════════════════════════════════════
// REQUEST DTOs
// ══════════════════════════════════════════════════════════════════════════════
 
// -- StartSessionRequest.java --
@Data @NoArgsConstructor @AllArgsConstructor
public class StartSessionRequest {
    private String sessionId;
}