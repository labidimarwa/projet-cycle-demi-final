package com.nexgenai.controller;

import com.nexgenai.dto.hr.ApplicationDecisionRequest;
import com.nexgenai.dto.hr.ApplicationDecisionResponse;
import com.nexgenai.dto.hr.CandidateApplicationDetailDTO;
import com.nexgenai.dto.hr.JobCandidatesResponse;
import com.nexgenai.service.HrService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * HR endpoint: view all candidates who applied to a specific job,
 * with full detail (profile, match score, chat session, voice transcript).
 *
 * Phase 2 refactoring: delegates to the unified {@link HrService} instead
 * of the now-removed {@code HrCandidatesService} and {@code HrDecisionService}.
 */
@RestController
@RequestMapping("/hr/jobs/{jobId}/candidates")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:4200")
@PreAuthorize("hasRole('HR') or hasRole('ADMIN')")
public class HrCandidatesController {

    private final HrService hrService;

    /**
     * GET /hr/jobs/{jobId}/candidates
     * Returns a summary list of all applicants for a job.
     */
    @GetMapping
    public ResponseEntity<JobCandidatesResponse> getCandidatesForJob(
            @PathVariable String jobId) {
        return ResponseEntity.ok(hrService.getCandidatesForJob(jobId));
    }

    /**
     * GET /hr/jobs/{jobId}/candidates/{candidateId}
     * Returns full detail for one candidate: profile + match + chat + voice.
     */
    @GetMapping("/{candidateId}")
    public ResponseEntity<CandidateApplicationDetailDTO> getCandidateDetail(
            @PathVariable String jobId,
            @PathVariable String candidateId) {
        return ResponseEntity.ok(hrService.getCandidateDetail(jobId, candidateId));
    }

    /**
     * POST /hr/jobs/{jobId}/candidates/{candidateId}/decision
     * Body: { "decision": "ACCEPTED" | "REJECTED", "note": "optional HR note" }
     */
    @PostMapping("/{candidateId}/decision")
    public ResponseEntity<ApplicationDecisionResponse> makeDecision(
            @PathVariable String jobId,
            @PathVariable String candidateId,
            @RequestBody ApplicationDecisionRequest req) {
        return ResponseEntity.ok(hrService.decide(jobId, candidateId, req));
    }
}
