package com.nexgenai.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.nexgenai.dto.jobtest.JobTestDtos.*;
import com.nexgenai.dto.hr.ApplicationDecisionRequest;
import com.nexgenai.dto.hr.ApplicationDecisionResponse;
import com.nexgenai.repository.UserRepository;
import com.nexgenai.service.AssessmentService;
import com.nexgenai.service.HrService;
import com.nexgenai.dto.technicaltest.AntiCheatReportDto;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Assessment management endpoints (evaluator / admin facing).
 * Maintains the original /job-tests path for backward compatibility.
 */
@RestController
@RequestMapping("/job-tests")
@RequiredArgsConstructor
public class JobTestController {

    private final AssessmentService svc;
    private final UserRepository    userRepository;
    private final HrService         hrService;

    // ══════════════════════════════════════════════════════════════════════════
    // ANTI-CHEAT (accessible aux évaluateurs)
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/sessions/{sessionId}/anti-cheat")
    public ResponseEntity<AntiCheatReportDto> getAntiCheatReportForEvaluator(
            @PathVariable String sessionId) {
        return ResponseEntity.ok(svc.getAntiCheatReport(sessionId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DECISION ÉVALUATEUR
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/{testId}/candidates/{candidateId}/decision")
    public ResponseEntity<ApplicationDecisionResponse> makeEvaluatorDecision(
            @PathVariable String testId,
            @PathVariable String candidateId,
            @RequestBody ApplicationDecisionRequest req) {

        JobTestResponse testResponse = svc.getTest(testId);
        String jobId = testResponse.getJobId();
        return ResponseEntity.ok(hrService.decide(jobId, candidateId, req));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CANDIDATE FULL RESULT (answers)
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/{testId}/candidates/{candidateId}/answers")
    public ResponseEntity<CandidateFullResultResponse> getCandidateFullResult(
            @PathVariable String testId,
            @PathVariable String candidateId) {
        return ResponseEntity.ok(svc.getCandidateFullResult(testId, candidateId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JOBS WITH TESTS
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/jobs-with-tests")
    public ResponseEntity<List<JobWithTestsResponse>> getJobsWithTests() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        String role = auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith("ROLE_"))
            .map(a -> a.replace("ROLE_", ""))
            .findFirst().orElse("ADMIN");

        String email = auth.getName();
        String evaluatorId = userRepository.findByEmail(email)
            .map(u -> u.getId())
            .orElse(null);

        return ResponseEntity.ok(svc.getJobsWithTests(role, evaluatorId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CONFIGURE FROM STAGE
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/configure-from-stage")
    public ResponseEntity<JobTestResponse> configureFromStage(
            @RequestParam String workflowStageId,
            @RequestParam String jobId) {
        return ResponseEntity.ok(svc.configureFromStage(workflowStageId, jobId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PSYCHOMETRIC MODELS
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/models")
    public ResponseEntity<List<ModelResponse>> getAllModels() {
        return ResponseEntity.ok(svc.getAllModels());
    }

    @PostMapping("/models")
    public ResponseEntity<ModelResponse> createModel(@RequestBody CreateModelRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(svc.createModel(req));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TESTS — CRUD
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping
    public ResponseEntity<List<JobTestResponse>> getAllTests() {
        return ResponseEntity.ok(svc.getAllTests());
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobTestResponse> getTest(@PathVariable String id) {
        return ResponseEntity.ok(svc.getTest(id));
    }

    @PostMapping
    public ResponseEntity<JobTestResponse> createTest(@RequestBody CreateTestRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(svc.createTest(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<JobTestResponse> updateTest(@PathVariable String id,
                                                      @RequestBody UpdateTestRequest req) {
        return ResponseEntity.ok(svc.updateTest(id, req));
    }

    @PutMapping("/{id}/full")
    public ResponseEntity<JobTestResponse> saveFullTest(@PathVariable String id,
                                                        @RequestBody SaveFullTestRequest req) {
        return ResponseEntity.ok(svc.saveFullTest(id, req));
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<Void> activateTest(@PathVariable String id) {
        svc.activateTest(id);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}/archive")
    public ResponseEntity<Void> archiveTest(@PathVariable String id) {
        svc.archiveTest(id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTest(@PathVariable String id) {
        svc.deleteTest(id);
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // THEMES
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/{testId}/themes")
    public ResponseEntity<JobTestResponse> addTheme(@PathVariable String testId,
                                                    @RequestBody CreateThemeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(svc.addTheme(testId, req));
    }

    @DeleteMapping("/{testId}/themes/{themeId}")
    public ResponseEntity<Void> deleteTheme(@PathVariable String testId,
                                            @PathVariable String themeId) {
        svc.deleteTheme(testId, themeId);
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // THEME MODELS (psychométrique)
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/{testId}/themes/{themeId}/models")
    public ResponseEntity<JobTestResponse> addModelToTheme(
            @PathVariable String testId,
            @PathVariable String themeId,
            @RequestBody AddModelRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(svc.addModelToTheme(testId, themeId, req));
    }

    @PatchMapping("/{testId}/themes/{themeId}/models/{themeModelId}/weight")
    public ResponseEntity<Void> updateWeight(
            @PathVariable String testId,
            @PathVariable String themeId,
            @PathVariable String themeModelId,
            @RequestBody UpdateWeightRequest req) {
        svc.updateThemeModelWeight(testId, themeId, themeModelId, req);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{testId}/themes/{themeId}/models/{themeModelId}")
    public ResponseEntity<Void> removeModel(
            @PathVariable String testId,
            @PathVariable String themeId,
            @PathVariable String themeModelId) {
        svc.removeModelFromTheme(testId, themeId, themeModelId);
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // QUESTIONS PSYCHOMÉTRIQUES
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/theme-models/{themeModelId}/questions")
    public ResponseEntity<QuestionResponse> addQuestion(
            @PathVariable String themeModelId,
            @RequestBody CreateQuestionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(svc.addPsychometricQuestion(themeModelId, req));
    }

    @PutMapping("/questions/{questionId}")
    public ResponseEntity<QuestionResponse> updateQuestion(
            @PathVariable String questionId,
            @RequestBody CreateQuestionRequest req) {
        return ResponseEntity.ok(svc.updatePsychometricQuestion(questionId, req));
    }

    @DeleteMapping("/questions/{questionId}")
    public ResponseEntity<Void> deleteQuestion(@PathVariable String questionId) {
        svc.deleteQuestion(questionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/questions/{questionId}/image",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<QuestionResponse> uploadImage(
            @PathVariable String questionId,
            @RequestParam("image") MultipartFile file) throws IOException {
        return ResponseEntity.ok(svc.uploadQuestionImage(questionId, file));
    }

    @GetMapping("/questions/{questionId}/image")
    public ResponseEntity<Resource> serveImage(@PathVariable String questionId) {
        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/questions/{questionId}/image")
    public ResponseEntity<Void> deleteImage(@PathVariable String questionId) throws IOException {
        svc.deleteQuestionImage(questionId);
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SIMPLE QUESTIONS
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/themes/{themeId}/simple-questions")
    public ResponseEntity<SimpleQuestionResponse> addSimpleQuestion(
            @PathVariable String themeId,
            @RequestParam String type,
            @RequestBody SaveFullTestRequest.QuestionPayload req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(svc.addSimpleQuestion(themeId, req, type));
    }

    @PutMapping("/simple-questions/{questionId}")
    public ResponseEntity<SimpleQuestionResponse> updateSimpleQuestion(
            @PathVariable String questionId,
            @RequestBody SaveFullTestRequest.QuestionPayload req) {
        return ResponseEntity.ok(svc.updateSimpleQuestion(questionId, req));
    }

    @DeleteMapping("/simple-questions/{questionId}")
    public ResponseEntity<Void> deleteSimpleQuestion(@PathVariable String questionId) {
        svc.deleteSimpleQuestion(questionId);
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CANDIDATE RESULTS — générique
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/{testId}/candidates")
    public ResponseEntity<TestCandidatesResponse> getCandidatesForTest(
            @PathVariable String testId) {
        return ResponseEntity.ok(svc.getCandidatesForTest(testId));
    }

    @GetMapping("/{testId}/candidates/{candidateId}")
    public ResponseEntity<CandidateTestResultResponse> getCandidateResult(
            @PathVariable String testId,
            @PathVariable String candidateId) {
        return ResponseEntity.ok(svc.getCandidateResult(testId, candidateId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RH CANDIDATES
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/{testId}/rh-candidates")
    public ResponseEntity<TestCandidatesResponse> getRhCandidatesForTest(
            @PathVariable String testId) {
        return ResponseEntity.ok(svc.getRhCandidatesForTest(testId));
    }

    @GetMapping("/{testId}/rh-candidates/{candidateId}/result")
    public ResponseEntity<CandidateTestResultResponse> getRhCandidateResult(
            @PathVariable String testId,
            @PathVariable String candidateId) {
        return ResponseEntity.ok(svc.getRhCandidateResult(testId, candidateId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TECH CANDIDATES
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/{testId}/tech-candidates")
    public ResponseEntity<TestCandidatesResponse> getTechCandidatesForTest(
            @PathVariable String testId) {
        return ResponseEntity.ok(svc.getTechCandidatesForTest(testId));
    }

    @GetMapping("/{testId}/tech-candidates/{candidateId}/result")
    public ResponseEntity<CandidateTestResultResponse> getTechCandidateResult(
            @PathVariable String testId,
            @PathVariable String candidateId) {
        return ResponseEntity.ok(svc.getTechCandidateResult(testId, candidateId));
    }
}
