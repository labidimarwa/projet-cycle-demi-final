package com.nexgenai.controller;

import com.nexgenai.dto.technicaltest.*;
import com.nexgenai.service.TestSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/candidate/technical-tests")
@RequiredArgsConstructor
public class TechnicalTestController {

    private final TestSessionService svc;

    @PostMapping("/{testId}/session")
    public ResponseEntity<TechnicalSessionDto> startSession(
            @PathVariable String testId,
            @RequestBody StartSessionRequest req,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(svc.startTechnicalSession(testId, req.getSessionId(), user.getUsername()));
    }

    @PostMapping("/run")
    public ResponseEntity<List<TestCaseResultDto>> runCode(
            @RequestBody RunCodeRequest req,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(svc.runCode(req));
    }

    @PostMapping("/sessions/{sessionId}/answer")
    public ResponseEntity<Void> saveAnswer(
            @PathVariable String sessionId,
            @RequestBody SaveAnswerRequest req,
            @AuthenticationPrincipal UserDetails user) {
        svc.saveTechnicalAnswer(sessionId, req.getQuestionId(), req.getAnswer(), user.getUsername());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/sessions/{sessionId}/submit")
    public ResponseEntity<SubmitResultDto> submitTest(
            @PathVariable String sessionId,
            @RequestBody SubmitTestRequest req,
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(svc.submitTechnicalTest(sessionId, req, user.getUsername()));
    }

    @PostMapping("/sessions/{sessionId}/anti-cheat")
    public ResponseEntity<Void> logAntiCheat(
            @PathVariable String sessionId,
            @RequestBody AntiCheatEventDto event,
            @AuthenticationPrincipal UserDetails user) {
        svc.logAntiCheatEvent(sessionId, event, user.getUsername());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/sessions/{sessionId}/anti-cheat")
    public ResponseEntity<AntiCheatReportDto> getAntiCheatReport(
            @PathVariable String sessionId) {
        return ResponseEntity.ok(svc.getAntiCheatReport(sessionId));
    }
}
