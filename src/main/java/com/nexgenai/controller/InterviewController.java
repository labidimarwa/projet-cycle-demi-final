// InterviewController.java
package com.nexgenai.controller;

import com.nexgenai.dto.interview.InterviewDtos.*;
import com.nexgenai.model.User;
import com.nexgenai.repository.UserRepository;
import com.nexgenai.service.InterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/interviews")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "http://localhost:4200")
public class InterviewController {

    private final InterviewService interviewService;
    private final UserRepository userRepository;

    // ── GET /api/v1/interviews/my ─────────────────────────────────────────────
 // InterviewController.java
    @GetMapping("/my")
    public ResponseEntity<List<InterviewSummaryResponse>> getMyInterviews(
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            String email = userDetails.getUsername();
            log.info("==> email extrait: {}", email);
            
            // ✅ CHANGE ICI — findByEmail au lieu de findActiveUserByEmail
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found: " + email));
            
            log.info("==> user.getId(): {}", user.getId());
            
            List<InterviewSummaryResponse> result = interviewService.getInterviewsForUser(user.getId());
            log.info("==> résultats: {}", result.size());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("❌ ERREUR /interviews/my: {}", e.getMessage(), e);
            throw e;
        }
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

    // ── POST /api/v1/interviews/bootstrap ────────────────────────────────────
    // One-shot endpoint to create missing interviews for all existing jobs
    @PostMapping("/bootstrap")
    public ResponseEntity<String> bootstrap() {
        interviewService.bootstrapInterviewsForAllJobs();
        return ResponseEntity.ok("Bootstrap completed");
    }
}