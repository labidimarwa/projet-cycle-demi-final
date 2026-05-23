package com.nexgenai.controller;

import com.nexgenai.dto.test.*;
import com.nexgenai.service.TestSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoints pour le passage de test côté candidat (RH / psychométrique).
 * Base: /api/v1/candidate/tests
 */
@RestController
@RequestMapping("/candidate/tests")
@RequiredArgsConstructor
public class TestSessionController {

    private final TestSessionService testSessionService;

    @GetMapping("/{testId}/questions")
    public ResponseEntity<TestSessionDto> getTestQuestions(
            @PathVariable String testId,
            @RequestParam(required = false) String sessionId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(
                testSessionService.getRhTestSession(testId, sessionId, userDetails.getUsername()));
    }

    @GetMapping("/{testId}/answers")
    public ResponseEntity<Map<String, java.util.List<String>>> getSavedAnswers(
            @PathVariable String testId,
            @RequestParam String sessionId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(
                testSessionService.getRhSavedAnswers(sessionId, userDetails.getUsername()));
    }

    @PostMapping("/{testId}/answer")
    public ResponseEntity<Void> saveAnswer(
            @PathVariable String testId,
            @RequestBody SaveAnswerRequest req,
            @AuthenticationPrincipal UserDetails userDetails) {
        testSessionService.saveRhAnswer(req, userDetails.getUsername());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{testId}/submit")
    public ResponseEntity<Map<String, Integer>> submitTest(
            @PathVariable String testId,
            @RequestBody SubmitTestRequest req,
            @AuthenticationPrincipal UserDetails userDetails) {
        int score = testSessionService.submitRhTest(req.getSessionId(), userDetails.getUsername());
        return ResponseEntity.ok(Map.of("score", score));
    }
}
