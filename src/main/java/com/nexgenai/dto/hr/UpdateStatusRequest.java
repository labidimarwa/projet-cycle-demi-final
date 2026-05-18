package com.nexgenai.dto.hr;
 
import lombok.Data;
 
/**
 * Body for PATCH /hr/jobs/{jobId}/applicants/{candidateId}/status
 */
@Data
public class UpdateStatusRequest {
    /** PENDING | REVIEWED | SHORTLISTED | REJECTED | HIRED */
    private String status;
}
 