package com.nexgenai.controller;

import com.nexgenai.dto.candidate.StageProgressDTO;
import com.nexgenai.dto.hr.ApplicantDetailResponse;
import com.nexgenai.dto.hr.ApplicantSummaryResponse;
import com.nexgenai.dto.hr.UpdateStatusRequest;
import com.nexgenai.dto.hr.UpdateStageRequest;
import com.nexgenai.model.ApplicationStageProgress;
import com.nexgenai.model.enums.StageProgressStatus;
import com.nexgenai.service.ApplicationStageProgressService;
import com.nexgenai.service.HrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/hr")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "http://localhost:4200")
public class HrController {

    private final HrService                       hrService;
    private final ApplicationStageProgressService stageProgressService;

    // ═══════════════════════════════════════════════════════════════════════════
    // APPLICANTS — list & detail
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * GET /hr/jobs/{jobId}/applicants
     * Returns the summary list of all candidates who applied to a job.
     */
    @GetMapping("/jobs/{jobId}/applicants")
    public ResponseEntity<List<ApplicantSummaryResponse>> getApplicants(
            @PathVariable String jobId) {
        return ResponseEntity.ok(hrService.getApplicants(jobId));
    }

    /**
     * GET /hr/jobs/{jobId}/applicants/{candidateId}
     * Returns full detail for one candidate on a specific job.
     */
    @GetMapping("/jobs/{jobId}/applicants/{candidateId}")
    public ResponseEntity<ApplicantDetailResponse> getApplicantDetail(
            @PathVariable String jobId,
            @PathVariable String candidateId) {
        return ResponseEntity.ok(hrService.getApplicantDetail(jobId, candidateId));
    }

    /**
     * PATCH /hr/jobs/{jobId}/applicants/{candidateId}/status
     * Body: { "status": "SHORTLISTED" }
     * Updates the application status (PENDING | REVIEWED | SHORTLISTED | REJECTED | HIRED).
     */
    @PatchMapping("/jobs/{jobId}/applicants/{candidateId}/status")
    public ResponseEntity<Void> updateApplicantStatus(
            @PathVariable String jobId,
            @PathVariable String candidateId,
            @RequestBody UpdateStatusRequest req) {
        hrService.updateApplicantStatus(jobId, candidateId, req.getStatus());
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /hr/applicants/{candidateId}/cv
     * Downloads the CV of a candidate.
     */
    @GetMapping("/applicants/{candidateId}/cv")
    public ResponseEntity<Resource> downloadCv(@PathVariable String candidateId) {
        try {
            Path filePath = hrService.getCvPath(candidateId);
            Resource res  = new UrlResource(filePath.toUri());
            if (!res.exists()) return ResponseEntity.notFound().build();
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"cv_" + candidateId + ".pdf\"")
                .body(res);
        } catch (Exception e) {
            log.error("CV download failed for {}: {}", candidateId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PROCESS MAPPING — stage progress management by HR
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * GET /hr/candidates/{candidateId}/jobs/{jobId}/stages
     * Returns the ordered list of process stages for a (candidate, job) pair.
     * Useful for the HR candidate detail view to show where the candidate is.
     */
    @GetMapping("/candidates/{candidateId}/jobs/{jobId}/stages")
    public ResponseEntity<List<StageProgressDTO>> getStages(
            @PathVariable String candidateId,
            @PathVariable String jobId) {

        List<ApplicationStageProgress> rows =
            stageProgressService.getProgress(candidateId, jobId);

        List<StageProgressDTO> dtos = rows.stream()
            .map(p -> new StageProgressDTO(
                p.getId(),
                p.getStageOrder(),
                p.getStageName(),
                p.getStageType(),
                p.getStatus().name(),
                p.getStartedAt()   != null ? p.getStartedAt().toString()   : null,
                p.getCompletedAt() != null ? p.getCompletedAt().toString() : null,
                p.getHrNote()
            ))
            .toList();

        return ResponseEntity.ok(dtos);
    }

    /**
     * PATCH /hr/candidates/{candidateId}/jobs/{jobId}/stages/{stageOrder}
     * Body: { "status": "COMPLETED", "hrNote": "Passed technical interview" }
     *
     * HR manually advances or rolls back a stage.
     * When marked COMPLETED the next PENDING stage is automatically set to IN_PROGRESS.
     *
     * Valid status values: PENDING | IN_PROGRESS | COMPLETED | SKIPPED
     */
    @PatchMapping("/candidates/{candidateId}/jobs/{jobId}/stages/{stageOrder}")
    public ResponseEntity<StageProgressDTO> updateStage(
            @PathVariable String candidateId,
            @PathVariable String jobId,
            @PathVariable int    stageOrder,
            @RequestBody  UpdateStageRequest body) {

        // Validate status value early to return a clear 400
        StageProgressStatus newStatus;
        try {
            newStatus = StageProgressStatus.valueOf(body.getStatus().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        ApplicationStageProgress updated =
            stageProgressService.updateStageStatus(
                candidateId, jobId, stageOrder, newStatus, body.getHrNote());

        StageProgressDTO dto = new StageProgressDTO(
            updated.getId(),
            updated.getStageOrder(),
            updated.getStageName(),
            updated.getStageType(),
            updated.getStatus().name(),
            updated.getStartedAt()   != null ? updated.getStartedAt().toString()   : null,
            updated.getCompletedAt() != null ? updated.getCompletedAt().toString() : null,
            updated.getHrNote()
        );

        log.info("📋 HR advanced stage {} ({}) to {} for candidate {} / job {}",
            stageOrder, updated.getStageName(), newStatus, candidateId, jobId);

        return ResponseEntity.ok(dto);
    }
}