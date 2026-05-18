package com.nexgenai.dto.hr;
import lombok.Data;
 
@Data
public class UpdateStageRequest {
    private String status;   // PENDING | IN_PROGRESS | COMPLETED | SKIPPED
    private String hrNote;
}