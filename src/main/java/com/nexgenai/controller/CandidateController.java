// src/main/java/com/nexgenai/controller/CandidateController.java

package com.nexgenai.controller;

import com.nexgenai.dto.candidate.CandidateApplicationResponse;
import com.nexgenai.dto.candidate.CandidateProfileResponse;
import com.nexgenai.dto.candidate.StageProgressDTO;
import com.nexgenai.dto.candidate.UpdateCandidateProfileRequest;
import com.nexgenai.dto.technicaltest.AntiCheatEventDto;
import com.nexgenai.dto.technicaltest.AntiCheatReportDto;
import com.nexgenai.model.ApplicationStageProgress;
import com.nexgenai.model.Assessment;
import com.nexgenai.model.Candidate;
import com.nexgenai.model.JobMatch;
import com.nexgenai.model.TestSession;
import com.nexgenai.model.enums.AssessmentType;
import com.nexgenai.repository.AssessmentRepository;
import com.nexgenai.repository.CandidateRepository;
import com.nexgenai.repository.TestSessionRepository;
import com.nexgenai.service.AntiCheatService;
import com.nexgenai.service.ApplicationService;
import com.nexgenai.service.ApplicationStageProgressService;
import com.nexgenai.service.AssessmentService;
import com.nexgenai.service.CandidateApplicationService;
import com.nexgenai.service.CandidateService;
import com.nexgenai.service.ChatbotService;
import com.nexgenai.service.MatchingService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
    private final ChatbotService                  chatbotService;
    private final ApplicationStageProgressService stageProgressService;
    private final CandidateRepository             candidateRepository;
    private final MatchingService                 matchingService;
    private final AssessmentRepository            assessmentRepository;      // was JobTestRepository
    private final TestSessionRepository           testSessionRepository;     // unified
    private final AssessmentService               assessmentService;         // was TechnicalTestService
    private final AntiCheatService                antiCheatService;

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

        Candidate candidate = candidateRepository.findByEmail(u.getUsername())
                .orElseThrow(() -> new RuntimeException("Candidate not found"));

        List<ApplicationStageProgress> rows =
                stageProgressService.getProgress(candidate.getId(), jobId);

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
                .collect(Collectors.toList());

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

    // ── Chat: init session ────────────────────────────────────────────────────

    @PostMapping("/chat/init")
    public ResponseEntity<Map<String, Object>> initChat(
            @AuthenticationPrincipal UserDetails u,
            @RequestBody Map<String, String> body) {
        String jobId = body.get("jobId");
        if (jobId == null || jobId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "jobId required"));

        ChatbotService.InitResult result = chatbotService.initSession(u.getUsername(), jobId);

        Map<String, Object> response = new HashMap<>();
        response.put("sessionId",     result.sessionId());
        response.put("questionCount", result.questionCount());
        response.put("history",       result.history());
        return ResponseEntity.ok(response);
    }

    // ── Chat: text message ────────────────────────────────────────────────────

    @PostMapping("/chat/message")
    public ResponseEntity<Map<String, Object>> chat(
            @AuthenticationPrincipal UserDetails u,
            @RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String message   = body.get("message");
        if (sessionId == null || sessionId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "sessionId required"));
        if (message == null || message.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "message required"));

        ChatbotService.ChatResponse res = chatbotService.processMessage(sessionId, message);

        Map<String, Object> result = new HashMap<>();
        result.put("reply", res.reply());
        result.put("done",  res.done());
        if (res.score() != null) result.put("score", res.score());
        return ResponseEntity.ok(result);
    }

    // ── Chat: voice message ───────────────────────────────────────────────────

    @PostMapping(value = "/chat/voice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> chatVoice(
            @AuthenticationPrincipal UserDetails u,
            @RequestParam("sessionId") String sessionId,
            @RequestParam("audio")     MultipartFile audio) {
        String transcript = transcribeAudio(audio);
        if (transcript == null || transcript.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Could not transcribe audio. Please type your answer."));
        }
        ChatbotService.ChatResponse res = chatbotService.processMessage(sessionId, transcript);
        Map<String, Object> result = new HashMap<>();
        result.put("transcript", transcript);
        result.put("reply",      res.reply());
        result.put("done",       res.done());
        if (res.score() != null) result.put("score", res.score());
        return ResponseEntity.ok(result);
    }

    private String transcribeAudio(MultipartFile audio) {
        return null; // Whisper pas encore câblé — client utilise Web Speech API
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

        String candidateId = getCandidateId(userDetails.getUsername());

        var candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new RuntimeException("Candidate not found"));
        var assessment = assessmentRepository.findById(testId)
                .orElseThrow(() -> new RuntimeException("Assessment not found: " + testId));

        TestSession session = testSessionRepository
                .findByCandidateIdAndAssessmentId(candidateId, testId)
                .orElseGet(() -> {
                    TestSession s = TestSession.builder()
                            .candidate(candidate)
                            .assessment(assessment)
                            .type(assessment.getType())
                            .status(TestSession.SessionStatus.IN_PROGRESS)
                            .startedAt(LocalDateTime.now())
                            .build();
                    return testSessionRepository.save(s);
                });

        if (session.getStatus() == TestSession.SessionStatus.PENDING) {
            session.setStatus(TestSession.SessionStatus.IN_PROGRESS);
            session.setStartedAt(LocalDateTime.now());
            testSessionRepository.save(session);
        }

        return ResponseEntity.ok(Map.of(
                "sessionId", session.getId(),
                "status",    session.getStatus().name()
        ));
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

    
    
    @PostMapping("/matches/{jobId}/compute")
    public ResponseEntity<?> computeMatch(
            @AuthenticationPrincipal UserDetails u,
            @PathVariable String jobId) {
        try {
            log.info("🎯 Compute match: {} ↔ {}", u.getUsername(), jobId);
            
            // Vérifications rapides avant de lancer l'async
            Candidate c = candidateRepository.findByEmail(u.getUsername())
                .orElseThrow(() -> new RuntimeException("Candidate not found"));
            if (c.getCvPath() == null)
                return ResponseEntity.badRequest().body(Map.of("error", "No CV uploaded"));

            // Lance Ollama en arrière-plan, répond immédiatement
            matchingService.computeAsync(u.getUsername(), jobId);
            
            return ResponseEntity.ok(Map.of(
                "status",  "computing",
                "jobId",   jobId,
                "message", "AI scoring started, please wait..."
            ));
        } catch (RuntimeException e) {
            log.error("❌ Erreur compute: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
    // ── Helper ────────────────────────────────────────────────────────────────

    private String getCandidateId(String email) {
        return candidateRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Candidate not found: " + email))
                .getId();
    }
}
