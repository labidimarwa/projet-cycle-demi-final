package com.nexgenai.controller;

import com.nexgenai.dto.test.*;
import com.nexgenai.service.AssessmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoints pour le passage de test côté candidat (RH / psychométrique).
 * Base: /api/v1/candidate/tests
 *
 * Phase 2 refactoring: delegates to the unified {@link AssessmentService}
 * instead of the now-removed {@code TestSessionService}.
 */
@RestController
@RequestMapping("/candidate/tests")
@RequiredArgsConstructor
public class TestSessionController {

    private final AssessmentService assessmentService;

    /**
     * GET /candidate/tests/{testId}/questions?sessionId=
     * Retourne les questions du test + infos de session (pour reprendre).
     */
    @GetMapping("/{testId}/questions")
    public ResponseEntity<TestSessionDto> getTestQuestions(
            @PathVariable String testId,
            @RequestParam(required = false) String sessionId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(
                assessmentService.getRhTestSession(testId, sessionId, userDetails.getUsername()));
    }

    /**
     * GET /candidate/tests/{testId}/answers?sessionId=
     * Retourne les réponses déjà enregistrées (reprise de session).
     */
    @GetMapping("/{testId}/answers")
    public ResponseEntity<Map<String, java.util.List<String>>> getSavedAnswers(
            @PathVariable String testId,
            @RequestParam String sessionId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(
                assessmentService.getRhSavedAnswers(sessionId, userDetails.getUsername()));
    }

    /**
     * POST /candidate/tests/{testId}/answer
     * Sauvegarde une réponse à une question (auto-save).
     */
    @PostMapping("/{testId}/answer")
    public ResponseEntity<Void> saveAnswer(
            @PathVariable String testId,
            @RequestBody SaveAnswerRequest req,
            @AuthenticationPrincipal UserDetails userDetails) {
        assessmentService.saveRhAnswer(req, userDetails.getUsername());
        return ResponseEntity.ok().build();
    }

    /**
     * POST /candidate/tests/{testId}/submit
     * Soumet le test, calcule le score et clôture la session.
     */
    @PostMapping("/{testId}/submit")
    public ResponseEntity<Map<String, Integer>> submitTest(
            @PathVariable String testId,
            @RequestBody SubmitTestRequest req,
            @AuthenticationPrincipal UserDetails userDetails) {
        int score = assessmentService.submitRhTest(req.getSessionId(), userDetails.getUsername());
        return ResponseEntity.ok(Map.of("score", score));
    }
}
