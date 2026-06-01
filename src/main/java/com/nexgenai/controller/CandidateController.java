// src/main/java/com/nexgenai/controller/CandidateController.java

package com.nexgenai.controller;

import com.nexgenai.dto.candidate.CandidateApplicationResponse;
import com.nexgenai.dto.candidate.CandidateProfileResponse;
import com.nexgenai.dto.candidate.StageProgressDTO;
import com.nexgenai.dto.candidate.UpdateCandidateProfileRequest;
import com.nexgenai.dto.technicaltest.AntiCheatEventDto;
import com.nexgenai.dto.technicaltest.AntiCheatReportDto;
import com.nexgenai.model.ApplicationStageProgress;
import com.nexgenai.model.JobMatch;
import com.nexgenai.service.AntiCheatService;
import com.nexgenai.service.ApplicationService;
import com.nexgenai.service.ApplicationStageProgressService;
import com.nexgenai.service.CandidateService;
import com.nexgenai.service.CvMatchingService;
import com.nexgenai.service.TestSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Phase 2 refactoring: updated to use unified Assessment, TestSession models
 * and AssessmentService instead of legacy JobTest, TechnicalSession, and
 * TechnicalTestService.
 */
@RestController
@RequestMapping("/candidate")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "http://localhost:4200")
public class CandidateController {

    private final CandidateService                candidateService;
    private final ApplicationService              applicationService;
    private final ApplicationStageProgressService stageProgressService;
    private final CvMatchingService               cvMatchingService;
    private final TestSessionService              testSessionService;
    private final AntiCheatService                antiCheatService;
    private final ObjectMapper                    objectMapper;

    @org.springframework.beans.factory.annotation.Value("${matching.python.timeout:30000}")
    private long pythonTimeoutMs;

    // ── Profile ───────────────────────────────────────────────────────────────

    @GetMapping("/profile")
    public ResponseEntity<CandidateProfileResponse> getProfile(
            @AuthenticationPrincipal UserDetails u) {
        return ResponseEntity.ok(candidateService.getProfile(u.getUsername()));
    }

    @PutMapping("/profile")
    public ResponseEntity<CandidateProfileResponse> updateProfile(
            @AuthenticationPrincipal UserDetails u,
            @Valid @RequestBody UpdateCandidateProfileRequest req) {
        return ResponseEntity.ok(candidateService.updateProfile(u.getUsername(), req));
    }

    // ── CV ────────────────────────────────────────────────────────────────────

    @PostMapping("/cv")
    public ResponseEntity<Map<String, String>> uploadCv(
            @AuthenticationPrincipal UserDetails u,
            @RequestParam("file") MultipartFile file) {
        String cvPath = candidateService.uploadCv(u.getUsername(), file);
    
        return ResponseEntity.ok(Map.of(
                "cvPath",  cvPath,
                "message", "CV uploaded. AI analysis started..."));
    }

    @GetMapping("/cv")
    public ResponseEntity<Resource> downloadCv(@AuthenticationPrincipal UserDetails u) {
        try {
            Path filePath = candidateService.getCvFilePath(u.getUsername());
            Resource res  = new UrlResource(filePath.toUri());
            if (!res.exists()) return ResponseEntity.notFound().build();
            String display = candidateService.getCvDisplayName(u.getUsername());
            String media   = display.toLowerCase().endsWith(".pdf")
                    ? "application/pdf" : "application/octet-stream";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(media))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + display + "\"")
                    .body(res);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Match scores ──────────────────────────────────────────────────────────

    @GetMapping("/matches")
    public ResponseEntity<Map<String, Integer>> getMatchScores(
            @AuthenticationPrincipal UserDetails u) {
        return ResponseEntity.ok(candidateService.getMatchScores(u.getUsername()));
    }

    @GetMapping("/matches/{jobId}")
    public ResponseEntity<JobMatch> getMatchDetail(
            @AuthenticationPrincipal UserDetails u,
            @PathVariable String jobId) {
        Optional<JobMatch> match = candidateService.getMatchDetail(u.getUsername(), jobId);
        return match.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    // ── Applications ──────────────────────────────────────────────────────────

    /**
     * GET /api/v1/candidate/applications
     * Retourne toutes les candidatures avec testCategory dans chaque test.
     */
    @GetMapping("/applications")
    public ResponseEntity<List<CandidateApplicationResponse>> getApplications(
            @AuthenticationPrincipal UserDetails u) {
        return ResponseEntity.ok(candidateService.getApplications(u.getUsername()));
    }

    /**
     * GET /api/v1/candidate/applications/{jobId}/stages
     * Retourne les étapes de progression HR pour une candidature.
     */
    @GetMapping("/applications/{jobId}/stages")
    public ResponseEntity<List<StageProgressDTO>> getApplicationStages(
            @AuthenticationPrincipal UserDetails u,
            @PathVariable String jobId) {

        String candidateId = candidateService.getProfile(u.getUsername()).getId();
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

    // ── Apply to job ──────────────────────────────────────────────────────────

    @PostMapping(value = "/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> applyToJob(
            @AuthenticationPrincipal UserDetails u,
            @RequestParam("jobId")                           String jobId,
            @RequestParam(value = "cv",    required = false) MultipartFile cv,
            @RequestParam(value = "audio", required = false) MultipartFile audio) {
        applicationService.submitApplication(u.getUsername(), jobId, cv, audio);
        return ResponseEntity.ok(Map.of("message", "Application submitted!", "jobId", jobId));
    }

    // ── Test psychométrique (start session) ───────────────────────────────────

    /**
     * POST /api/v1/candidate/tests/{testId}/start
     * Démarre ou reprend une session de test (uses unified Assessment + TestSession).
     */
    @PostMapping("/tests/{testId}/start")
    public ResponseEntity<Map<String, String>> startTest(
            @PathVariable String testId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(testSessionService.startOrGetSession(testId, userDetails.getUsername()));
    }

    // ── Endpoint anti-cheat ───────────────────────────────────────────────────

    /**
     * POST /api/v1/candidate/tests/{testId}/anti-cheat
     * Enregistre un événement de triche détecté côté frontend.
     */
    @PostMapping("/tests/{testId}/anti-cheat")
    public ResponseEntity<Void> recordAntiCheatEvent(
            @PathVariable String testId,
            @RequestBody AntiCheatEventDto dto,
            @AuthenticationPrincipal UserDetails userDetails) {
        antiCheatService.recordEvent(testId, dto);
        return ResponseEntity.ok().build();
    }

    /**
     * GET /api/v1/candidate/tests/{testId}/anti-cheat/report?sessionId=xxx
     * Retourne le rapport complet (pour HR/Admin).
     */
    @GetMapping("/tests/{testId}/anti-cheat/report")
    public ResponseEntity<AntiCheatReportDto> getAntiCheatReport(
            @PathVariable String testId,
            @RequestParam String sessionId) {
        return ResponseEntity.ok(antiCheatService.buildReport(testId, sessionId));
    }

    
    
    @GetMapping(value = "/matches/{jobId}/compute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter computeMatch(
            @AuthenticationPrincipal UserDetails u,
            @PathVariable String jobId) {

        log.info("🎯 SSE compute match: {} ↔ {}", u.getUsername(), jobId);
        SseEmitter emitter = new SseEmitter(pythonTimeoutMs + 60_000L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> emitter.complete());

        var profile = candidateService.getProfile(u.getUsername());
        if (profile.getCvPath() == null) {
            try {
                emitter.send(SseEmitter.event().name("match-error")
                    .data(objectMapper.writeValueAsString(Map.of("error", "No CV uploaded"))));
                emitter.complete();
            } catch (Exception ignored) { // intentionally empty
            return emitter;
        }

        String candidateId = profile.getId();
        CompletableFuture.supplyAsync(() -> cvMatchingService.lancerMatching(jobId, candidateId, null, null))
            .whenComplete((result, ex) -> {
                try {
                    if (ex != null) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        emitter.send(SseEmitter.event().name("match-error")
                            .data(objectMapper.writeValueAsString(Map.of("error", cause.getMessage()))));
                        emitter.complete();
                    } else {
                        emitter.send(SseEmitter.event().name("match-ready")
                            .data(objectMapper.writeValueAsString(Map.of(
                                "jobId",   jobId,
                                "score",   result.getScoreGlobal(),
                                "verdict", result.getRecommendation(),
                                "resume",  result.getForceRejetRaison() != null ? result.getForceRejetRaison() : ""
                            ))));
                        emitter.complete();
                    }
                } catch (Exception e) {
                    log.warn("SSE send failed: {}", e.getMessage());
                    emitter.completeWithError(e);
                }
            });

        return emitter;
    }
}

