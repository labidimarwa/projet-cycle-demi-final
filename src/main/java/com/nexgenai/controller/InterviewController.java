// InterviewController.java
package com.nexgenai.controller;

import com.nexgenai.dto.interview.InterviewDtos.*;
import com.nexgenai.model.User;
import com.nexgenai.repository.UserRepository;
import com.nexgenai.service.InterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/interviews")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "http://localhost:4200")
public class InterviewController {

    private final InterviewService interviewService;
    private final UserRepository   userRepository;

    // ── GET /api/v1/interviews/my ─────────────────────────────────────────────
    @GetMapping("/my")
    public ResponseEntity<List<InterviewSummaryResponse>> getMyInterviews(
            @AuthenticationPrincipal UserDetails userDetails) {
        String email = userDetails.getUsername();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));
        return ResponseEntity.ok(interviewService.getInterviewsForUser(user.getId()));
    }

    // ── GET /api/v1/interviews/{id} ───────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<InterviewSummaryResponse> getInterview(@PathVariable String id) {
        return ResponseEntity.ok(interviewService.getInterview(id));
    }

    // ── PUT /api/v1/interviews/{id}/configure ─────────────────────────────────
    @PutMapping("/{id}/configure")
    public ResponseEntity<InterviewSummaryResponse> configure(
            @PathVariable String id,
            @RequestBody InterviewConfigRequest req) {
        return ResponseEntity.ok(interviewService.configure(id, req));
    }

    // ── GET /api/v1/interviews/{id}/slots ─────────────────────────────────────
    @GetMapping("/{id}/slots")
    public ResponseEntity<List<SlotResponse>> getSlots(@PathVariable String id) {
        return ResponseEntity.ok(interviewService.getSlots(id));
    }

    // ── POST /api/v1/interviews/{id}/generate-slots ───────────────────────────
    @PostMapping("/{id}/generate-slots")
    public ResponseEntity<List<SlotResponse>> generateSlots(@PathVariable String id) {
        return ResponseEntity.ok(interviewService.generateSlots(id));
    }

    // ── POST /api/v1/interviews/slots/{slotId}/evaluate ───────────────────────
    @PostMapping("/slots/{slotId}/evaluate")
    public ResponseEntity<SlotResponse> evaluate(
            @PathVariable String slotId,
            @RequestBody EvaluationSubmitRequest req) {
        return ResponseEntity.ok(interviewService.submitEvaluation(slotId, req));
    }

    // ── POST /api/v1/interviews/{id}/close ───────────────────────────────────
    /**
     * Manually closes a phase.
     * - Closing RH unlocks Technical configuration
     * - Closing Technical unlocks Admin configuration
     */
    @PostMapping("/{id}/close")
    public ResponseEntity<InterviewSummaryResponse> closePhase(
            @PathVariable String id,
            @RequestBody(required = false) ClosePhaseRequest req) {
        return ResponseEntity.ok(interviewService.closePhase(id, req));
    }

    // ── GET /api/v1/interviews/{id}/suggest-schedule ─────────────────────────
    /**
     * Returns an auto-calculated schedule suggestion for the given interview phase.
     * Optional query param: desiredStart (yyyy-MM-dd) — defaults to today if not provided.
     *
     * Response includes:
     *  - candidateCount, assigneeCount
     *  - roundsPerDay (how many parallel rounds fit per working day)
     *  - totalRoundsNeeded = ceil(candidates / assignees)
     *  - estimatedDaysNeeded
     *  - suggestedStartDate / suggestedEndDate
     *  - blockedDates (from previous phases)
     */
    @GetMapping("/{id}/suggest-schedule")
    public ResponseEntity<ScheduleSuggestionResponse> suggestSchedule(
            @PathVariable String id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desiredStart) {
        return ResponseEntity.ok(interviewService.suggestSchedule(id, desiredStart));
    }

    // ── GET /api/v1/interviews/jobs/{jobId}/phases ───────────────────────────
    /**
     * Returns the full phase pipeline status for a job:
     *  - RH, Technical, Admin interview summaries
     *  - canConfigureTechnical / canConfigureAdmin flags
     *  - earliestTechnicalStart / earliestAdminStart dates
     *  - Occupied dates from previous phases (blocked for scheduling)
     */
    @GetMapping("/jobs/{jobId}/phases")
    public ResponseEntity<JobPhasesStatusResponse> getJobPhases(@PathVariable String jobId) {
        return ResponseEntity.ok(interviewService.getJobPhases(jobId));
    }

    // ── GET /api/v1/interviews/candidate/my-slots ────────────────────────────
    @GetMapping("/candidate/my-slots")
    public ResponseEntity<List<CandidateSlotView>> getMyCandidateSlots(
            @AuthenticationPrincipal UserDetails userDetails) {
        String email = userDetails.getUsername();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));
        return ResponseEntity.ok(interviewService.getCandidateSlots(user.getId()));
    }

    // ── POST /api/v1/interviews/bootstrap ────────────────────────────────────
    @PostMapping("/bootstrap")
    public ResponseEntity<String> bootstrap() {
        interviewService.bootstrapInterviewsForAllJobs();
        return ResponseEntity.ok("Bootstrap completed");
    }
}
