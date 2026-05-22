package com.nexgenai.controller;

import com.nexgenai.dto.job.CreateJobRequest;
import com.nexgenai.dto.job.JobResponse;
import com.nexgenai.dto.job.UpdateJobRequest;
import com.nexgenai.model.enums.JobStatus;
import com.nexgenai.service.JobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/jobs")
@CrossOrigin(origins = "http://localhost:4200")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    // ── CREATE ────────────────────────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<JobResponse> createJob(@Valid @RequestBody CreateJobRequest req) {
        return new ResponseEntity<>(jobService.createJob(req), HttpStatus.CREATED);
    }

    // ── READ ALL ──────────────────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<List<JobResponse>> getAllJobs() {
        return ResponseEntity.ok(jobService.getAllJobs());
    }

    @GetMapping("/active")
    public ResponseEntity<List<JobResponse>> getActiveJobs() {
        return ResponseEntity.ok(jobService.getActiveJobs());
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> getJobById(@PathVariable String id) {
        return ResponseEntity.ok(jobService.getJobById(id));
    }

    @GetMapping("/department/{department}")
    public ResponseEntity<List<JobResponse>> getByDepartment(@PathVariable String department) {
        return ResponseEntity.ok(jobService.getJobsByDepartment(department));
    }

    // ── FULL UPDATE ───────────────────────────────────────────────────────────
    @PutMapping("/{id}")
    public ResponseEntity<JobResponse> updateJob(
            @PathVariable String id,
            @Valid @RequestBody CreateJobRequest req) {
        return ResponseEntity.ok(jobService.updateJob(id, req));
    }

    // ── PARTIAL UPDATE (PATCH) ────────────────────────────────────────────────
    @PatchMapping("/{id}")
    public ResponseEntity<JobResponse> patchJob(
            @PathVariable String id,
            @RequestBody UpdateJobRequest req) {
        return ResponseEntity.ok(jobService.patchJob(id, req));
    }

    // ── STATUS CHANGE ─────────────────────────────────────────────────────────
    /**
     * PATCH /jobs/{id}/status
     * Body: { "status": "ACTIVE" | "HIDDEN" | "CLOSED" | "DRAFT" | "ARCHIVED" }
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<JobResponse> changeStatus(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String statusStr = body.get("status");
        if (statusStr == null)
            return ResponseEntity.badRequest().build();
        try {
            JobStatus newStatus = JobStatus.valueOf(statusStr.toUpperCase());
            return ResponseEntity.ok(jobService.changeStatus(id, newStatus));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ── DELETE ────────────────────────────────────────────────────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteJob(@PathVariable String id) {
        jobService.deleteJob(id);
        return ResponseEntity.noContent().build();
    }
    
    
    
    
    @PostMapping("/{id}/remote-link")
    public ResponseEntity<Map<String, String>> generateRemoteLink(@PathVariable String id) {
        String link = jobService.generateLink(id);
        return ResponseEntity.ok(Map.of("link", link));
    }
}