package com.nexgenai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexgenai.dto.hr.AnswerDecisionRequest;
import com.nexgenai.dto.hr.AnswerDecisionResponse;
import com.nexgenai.dto.jobtest.JobTestDtos.*;
import com.nexgenai.dto.technicaltest.*;
import com.nexgenai.model.*;
import com.nexgenai.model.enums.AntiCheatRiskLevel;
import com.nexgenai.model.enums.AssessmentType;
import com.nexgenai.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the lifecycle of test sessions: start, answer, submit (both technical and RH),
 * and anti-cheat event logging.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TestSessionService {

    private final TestSessionRepository      testSessionRepository;
    private final AssessmentRepository       assessmentRepository;
    private final QuestionRepository         questionRepository;
    private final CandidateRepository        candidateRepository;
    private final AntiCheatEventRepository   antiCheatRepository;
    private final TestSubmissionRepository   submissionRepository;
    private final CandidateAnswerRepository  answerRepository;
    private final WorkflowStageRepository    workflowStageRepository;

    private final CodeExecutionService codeExecutionService;
    private final ObjectMapper         objectMapper;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    // ══════════════════════════════════════════════════════════════════════════
    // START OR GET SESSION (used by CandidateController)
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public Map<String, String> startOrGetSession(String assessmentId, String email) {
        Candidate candidate = candidateRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Candidate not found"));
        Assessment assessment = assessmentRepository.findByIdWithThemes(assessmentId)
                .orElseThrow(() -> new RuntimeException("Assessment not found: " + assessmentId));

        TestSession session = testSessionRepository
                .findByCandidateIdAndAssessmentId(candidate.getId(), assessmentId)
                .orElseGet(() -> {
                    TestSession s = TestSession.builder()
                            .candidate(candidate).assessment(assessment)
                            .type(assessment.getType())
                            .status(TestSession.SessionStatus.IN_PROGRESS)
                            .startedAt(LocalDateTime.now())
                            .answersJson("{}").antiCheatJson("[]").build();
                    return testSessionRepository.save(s);
                });

        return Map.of(
                "sessionId", session.getId(),
                "status",    session.getStatus().name()
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TECHNICAL SESSION
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public TechnicalSessionDto startTechnicalSession(String assessmentId, String existingSessionId, String email) {
        Candidate candidate = candidateRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Candidate not found"));
        Assessment assessment = assessmentRepository.findByIdWithThemes(assessmentId)
                .orElseThrow(() -> new RuntimeException("Assessment not found: " + assessmentId));

        TestSession session;
        if (existingSessionId != null && !existingSessionId.isBlank()) {
            session = testSessionRepository.findById(existingSessionId)
                    .orElseGet(() -> createTechnicalSession(candidate, assessment));
        } else {
            session = testSessionRepository
                    .findByCandidateIdAndAssessmentId(candidate.getId(), assessmentId)
                    .orElseGet(() -> createTechnicalSession(candidate, assessment));
        }

        if (session.getStatus() == TestSession.SessionStatus.COMPLETED)
            throw new RuntimeException("Test already submitted");

        Map<String, Object> savedAnswers = parseSavedAnswers(session.getAnswersJson());

        List<SimpleQuestionDto> questions = assessment.getThemes().stream()
                .flatMap(theme -> questionRepository.findByThemeIdOrderByOrderIndex(theme.getId()).stream())
                .map(q -> mapToTechnicalDto(q, savedAnswers.get(q.getId())))
                .collect(Collectors.toList());

        int timeLimitSeconds = computeTimeLimit(questions);
        session.setTimeLimitSeconds(timeLimitSeconds);
        testSessionRepository.save(session);

        return TechnicalSessionDto.builder()
                .sessionId(session.getId()).testId(assessmentId)
                .testName(assessment.getName()).jobTitle(assessment.getJob().getTitle())
                .timeLimitSeconds(timeLimitSeconds).questions(questions).build();
    }

    public List<TestCaseResultDto> runCode(RunCodeRequest req, String email) {
        return codeExecutionService.execute(req.getCode(), req.getLanguage(), req.getTestCases());
    }

    @Transactional
    public void saveTechnicalAnswer(String sessionId, String questionId, Object answer, String email) {
        TestSession session = testSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));
        if (session.getStatus() == TestSession.SessionStatus.COMPLETED) return;

        Map<String, Object> answers = parseSavedAnswers(session.getAnswersJson());
        answers.put(questionId, answer);
        try {
            session.setAnswersJson(objectMapper.writeValueAsString(answers));
            testSessionRepository.save(session);
        } catch (Exception e) {
            log.warn("Could not save answer for session {}", sessionId);
        }
    }

    @Transactional
    public SubmitResultDto submitTechnicalTest(String sessionId, SubmitTestRequest req, String email) {
        TestSession session = testSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));
        if (session.getStatus() == TestSession.SessionStatus.COMPLETED)
            throw new RuntimeException("Already submitted");

        if (req.getAnswers() != null && !req.getAnswers().isEmpty()) {
            try { session.setAnswersJson(objectMapper.writeValueAsString(req.getAnswers())); }
            catch (Exception ignored) {}
        }
        if (req.getAntiCheatLog() != null) {
            try { session.setAntiCheatJson(objectMapper.writeValueAsString(req.getAntiCheatLog())); }
            catch (Exception ignored) {}
        }

        Map<String, Object> answers = parseSavedAnswers(session.getAnswersJson());
        Assessment assessment = session.getAssessment();
        List<QuestionResultDto> questionResults = new ArrayList<>();
        int totalPoints = 0, earnedPoints = 0;

        for (TestTheme theme : assessment.getThemes()) {
            List<Question> questions = questionRepository.findByThemeIdOrderByOrderIndex(theme.getId());
            for (Question q : questions) {
                Object rawAnswer = answers.get(q.getId());
                int maxPts = q.getPoints() != null ? q.getPoints() : 10;
                totalPoints += maxPts;
                int pts = scoreQuestion(q, rawAnswer, answers, req);
                earnedPoints += pts;
                questionResults.add(QuestionResultDto.builder()
                        .questionId(q.getId()).title(q.getTitle())
                        .type(q.getKind() != null ? q.getKind().name() : "QCM")
                        .earnedPoints(pts).maxPoints(maxPts).build());
            }
        }

        try { session.setAnswersJson(objectMapper.writeValueAsString(answers)); }
        catch (Exception e) { log.warn("Could not re-save enriched answers for session {}", sessionId); }

        double score = totalPoints > 0
                ? Math.round((double) earnedPoints / totalPoints * 1000.0) / 10.0 : 0.0;

        session.setStatus(TestSession.SessionStatus.COMPLETED);
        session.setCompletedAt(LocalDateTime.now());
        session.setScore((int) score);
        session.setTotalPoints(totalPoints);
        session.setEarnedPoints(earnedPoints);
        session.setDurationSeconds(req.getDurationSeconds());
        session.setRiskLevel(computeRiskLevel(req.getAntiCheatLog()).name());
        testSessionRepository.save(session);

        log.info("Assessment {} submitted by {}. Score: {}%", sessionId, email, score);

        return SubmitResultDto.builder()
                .score(score).totalPoints(totalPoints).earnedPoints(earnedPoints)
                .questionsResults(questionResults).build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RH SESSION
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public com.nexgenai.dto.test.TestSessionDto getRhTestSession(String assessmentId, String sessionId, String email) {
        Candidate candidate = candidateRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Candidate not found"));
        Assessment assessment = assessmentRepository.findByIdWithThemes(assessmentId)
                .orElseThrow(() -> new RuntimeException("Assessment not found: " + assessmentId));

        TestSession session;
        if (sessionId != null && !sessionId.isBlank()) {
            session = testSessionRepository.findById(sessionId)
                    .orElseGet(() -> createRhSession(candidate, assessment));
        } else {
            session = testSessionRepository
                    .findByCandidateIdAndAssessmentId(candidate.getId(), assessmentId)
                    .orElseGet(() -> createRhSession(candidate, assessment));
        }

        if (session.getStatus() == TestSession.SessionStatus.COMPLETED) {
            List<com.nexgenai.dto.test.QuestionDto> questions = buildRhQuestions(assessment);
            return com.nexgenai.dto.test.TestSessionDto.builder()
                    .sessionId(session.getId()).testId(assessmentId)
                    .testName(assessment.getName()).totalQuestions(questions.size())
                    .timeLimit(0).timeLeftSeconds(0).questions(questions).build();
        }

        int timeLimitMinutes  = resolveRhTimeLimit(assessment);
        int timeLimitSeconds  = timeLimitMinutes * 60;
        int timeLeftSeconds;
        if (session.getStartedAt() != null) {
            long elapsed = java.time.Duration.between(session.getStartedAt(), LocalDateTime.now()).getSeconds();
            timeLeftSeconds = (int) Math.max(0, timeLimitSeconds - elapsed);
        } else {
            timeLeftSeconds = timeLimitSeconds;
        }

        if (timeLeftSeconds == 0 && session.getStatus() == TestSession.SessionStatus.IN_PROGRESS) {
            submitRhTest(session.getId(), email);
        }

        List<com.nexgenai.dto.test.QuestionDto> questions = buildRhQuestions(assessment);

        return com.nexgenai.dto.test.TestSessionDto.builder()
                .sessionId(session.getId()).testId(assessmentId)
                .testName(assessment.getName()).totalQuestions(questions.size())
                .timeLimit(timeLimitMinutes).timeLeftSeconds(timeLeftSeconds)
                .questions(questions).build();
    }

    @Transactional
    public int submitRhTest(String sessionId, String email) {
        TestSession session = testSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));
        if (session.getStatus() == TestSession.SessionStatus.COMPLETED)
            return session.getScore() != null ? session.getScore() : 0;

        Map<String, List<String>> answers = new HashMap<>();
        try {
            if (session.getAnswersJson() != null)
                answers = objectMapper.readValue(session.getAnswersJson(),
                        new TypeReference<Map<String, List<String>>>() {});
        } catch (Exception ignored) {}

        Assessment assessment = assessmentRepository.findByIdWithThemes(session.getAssessment().getId())
                .orElseThrow(() -> new RuntimeException("Assessment not found"));

        int totalPoints = 0, earnedPoints = 0;

        for (TestTheme theme : assessment.getThemes()) {
            for (ThemeModel tm : theme.getThemeModels()) {
                int weight = (tm.getWeight() != null && tm.getWeight() > 0) ? tm.getWeight() : 1;
                for (Question question : tm.getQuestions()) {
                    List<QuestionOption> opts = new ArrayList<>(question.getOptions());
                    int maxPts = opts.stream().mapToInt(o -> o.getPoints() != null ? o.getPoints() : 0).max().orElse(0);
                    totalPoints += maxPts * weight;
                    List<String> chosen = answers.getOrDefault(question.getId(), List.of());
                    int pts = opts.stream().filter(o -> chosen.contains(o.getId()))
                            .mapToInt(o -> o.getPoints() != null ? o.getPoints() : 0).sum();
                    earnedPoints += Math.max(0, pts) * weight;
                }
            }
        }

        int score = totalPoints > 0 ? Math.round((float) earnedPoints / totalPoints * 100) : 0;
        session.setScore(score);
        session.setStatus(TestSession.SessionStatus.COMPLETED);
        session.setCompletedAt(LocalDateTime.now());
        testSessionRepository.save(session);

        log.info("RH test {} submitted by {} — Score: {}/100", sessionId, email, score);
        return score;
    }

    @Transactional(readOnly = true)
    public Map<String, List<String>> getRhSavedAnswers(String sessionId, String email) {
        TestSession session = testSessionRepository.findById(sessionId).orElse(null);
        if (session == null || session.getAnswersJson() == null) return Map.of();
        try {
            return objectMapper.readValue(session.getAnswersJson(),
                    new TypeReference<Map<String, List<String>>>() {});
        } catch (Exception e) {
            log.warn("Could not parse answers JSON for session {}", sessionId);
            return Map.of();
        }
    }

    @Transactional
    public void saveRhAnswer(com.nexgenai.dto.test.SaveAnswerRequest req, String email) {
        TestSession session = testSessionRepository.findById(req.getSessionId())
                .orElseThrow(() -> new RuntimeException("Session not found: " + req.getSessionId()));
        if (session.getStatus() == TestSession.SessionStatus.COMPLETED)
            throw new RuntimeException("Test already submitted");

        Map<String, List<String>> answers = new HashMap<>();
        try {
            if (session.getAnswersJson() != null && !session.getAnswersJson().isBlank())
                answers = objectMapper.readValue(session.getAnswersJson(),
                        new TypeReference<Map<String, List<String>>>() {});
        } catch (Exception ignored) {}

        answers.put(req.getQuestionId(), req.getOptionIds() != null ? req.getOptionIds() : List.of());
        try { session.setAnswersJson(objectMapper.writeValueAsString(answers)); }
        catch (Exception e) { throw new RuntimeException("Could not serialize answers", e); }
        testSessionRepository.save(session);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ANTI-CHEAT
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public void logAntiCheatEvent(String sessionId, AntiCheatEventDto event, String email) {
        TestSession session = testSessionRepository.findById(sessionId).orElse(null);
        if (session == null) return;

        List<Map<String, Object>> logEntries = parseAntiCheatLog(session.getAntiCheatJson());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", event.getType()); entry.put("detail", event.getDetail());
        entry.put("questionIndex", event.getQuestionIndex()); entry.put("timestamp", event.getTimestamp());
        logEntries.add(entry);

        try {
            session.setAntiCheatJson(objectMapper.writeValueAsString(logEntries));
            testSessionRepository.save(session);
        } catch (Exception ignored) {}
    }

    @Transactional(readOnly = true)
    public AntiCheatReportDto getAntiCheatReport(String sessionId) {
        TestSession session = testSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        List<AntiCheatEventDto> events = parseAntiCheatEvents(session.getAntiCheatJson());

        long tabSwitches = events.stream().filter(e -> "TAB_SWITCH".equals(e.getType())).count();
        long pastes      = events.stream().filter(e -> "PASTE".equals(e.getType())).count();
        long blurs       = events.stream().filter(e -> "WINDOW_BLUR".equals(e.getType())).count();
        long devTools    = events.stream()
                .filter(e -> "DEV_TOOLS".equals(e.getType()) || "DEVTOOLS_ATTEMPT".equals(e.getType())
                          || "DEVTOOLS".equals(e.getType())).count();

        int totalPts  = session.getTotalPoints()  != null ? session.getTotalPoints()  : 0;
        int earnedPts = session.getEarnedPoints() != null ? session.getEarnedPoints() : 0;

        return AntiCheatReportDto.builder()
                .sessionId(sessionId)
                .candidateName(session.getCandidate().getFirstName() + " " + session.getCandidate().getLastName())
                .testName(session.getAssessment().getName())
                .score(session.getScore() != null ? session.getScore() : 0)
                .totalPoints(totalPts).earnedPoints(earnedPts)
                .tabSwitchCount((int) tabSwitches).pasteCount((int) pastes)
                .blurCount((int) blurs).devToolsAttempts((int) devTools)
                .totalEvents(events.size())
                .riskLevel(session.getRiskLevel() != null ? session.getRiskLevel() : "LOW")
                .events(events)
                .questionsResults(buildAntiCheatQuestionResults(session))
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private TestSession createTechnicalSession(Candidate candidate, Assessment assessment) {
        return testSessionRepository.save(TestSession.builder()
                .candidate(candidate).assessment(assessment).type(AssessmentType.TECHNICAL)
                .status(TestSession.SessionStatus.IN_PROGRESS).startedAt(LocalDateTime.now())
                .answersJson("{}").antiCheatJson("[]").build());
    }

    private TestSession createRhSession(Candidate candidate, Assessment assessment) {
        return testSessionRepository.save(TestSession.builder()
                .candidate(candidate).assessment(assessment).type(AssessmentType.RH)
                .status(TestSession.SessionStatus.IN_PROGRESS).startedAt(LocalDateTime.now())
                .answersJson("{}").build());
    }

    private int computeTimeLimit(List<SimpleQuestionDto> questions) {
        int total = questions.stream().mapToInt(q -> {
            if ("PROBLEM_SOLVING".equals(q.getType()))
                return q.getTimeLimit() != null ? (int)(q.getTimeLimit() * 60) : 600;
            return 120;
        }).sum();
        return Math.max(900, Math.min(total, 10800));
    }

    private int resolveRhTimeLimit(Assessment assessment) {
        try {
            if (assessment.getDuration() != null && assessment.getDuration() > 0) return assessment.getDuration();
            if (assessment.getWorkflowStageId() != null) {
                Optional<WorkflowStage> stageOpt = workflowStageRepository.findById(assessment.getWorkflowStageId());
                if (stageOpt.isPresent() && stageOpt.get().getAssessmentId() != null) {
                    Optional<Assessment> linkedOpt = assessmentRepository.findByLinkId(stageOpt.get().getAssessmentId());
                    if (linkedOpt.isPresent() && linkedOpt.get().getDuration() != null
                            && linkedOpt.get().getDuration() > 0) return linkedOpt.get().getDuration();
                }
            }
            return 45;
        } catch (Exception e) {
            log.error("Error resolving time limit for assessment {}", assessment.getId(), e);
            return 45;
        }
    }

    private List<com.nexgenai.dto.test.QuestionDto> buildRhQuestions(Assessment assessment) {
        return assessment.getThemes().stream()
                .flatMap(th -> th.getThemeModels().stream())
                .flatMap(tm -> tm.getQuestions().stream())
                .map(this::mapRhQuestion)
                .collect(Collectors.toList());
    }

    private List<AntiCheatReportDto.QuestionResult> buildAntiCheatQuestionResults(TestSession session) {
        Assessment assessment = session.getAssessment();
        Map<String, Object> answers = parseSavedAnswers(session.getAnswersJson());
        List<AntiCheatReportDto.QuestionResult> results = new ArrayList<>();

        for (TestTheme theme : assessment.getThemes()) {
            List<Question> questions = questionRepository.findByThemeIdOrderByOrderIndex(theme.getId());
            if (questions.isEmpty() && !answers.isEmpty())
                questions = questionRepository.findByIdIn(new ArrayList<>(answers.keySet()));

            for (Question q : questions) {
                int maxPts  = q.getPoints() != null ? q.getPoints() : 0;
                int earned  = estimateEarnedFromAnswer(q, answers.get(q.getId()));
                results.add(AntiCheatReportDto.QuestionResult.builder()
                    .questionId(q.getId()).title(q.getTitle() != null ? q.getTitle() : "Question")
                    .type(q.getKind() != null ? q.getKind().name() : "QCM")
                    .earnedPoints(earned).maxPoints(maxPts).build());
            }
        }
        return results;
    }

    private int scoreQuestion(Question q, Object rawAnswer,
                              Map<String, Object> answers, SubmitTestRequest req) {
        if (rawAnswer == null) return 0;
        int max = q.getPoints() != null ? q.getPoints() : 10;
        try {
            if (q.getKind() == Question.QuestionKind.PROBLEM_SOLVING)
                return scoreProblemSolving(q, rawAnswer, answers, max);
            return scoreQcm(q, rawAnswer, max);
        } catch (Exception e) {
            log.warn("Error scoring question {}: {}", q.getId(), e.getMessage());
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private int scoreProblemSolving(Question q, Object rawAnswer,
                                    Map<String, Object> answers, int maxPts) {
        Map<String, Object> ans;
        try {
            ans = objectMapper.readValue(objectMapper.writeValueAsString(rawAnswer), new TypeReference<>() {});
        } catch (Exception e) { return 0; }

        String code     = (String) ans.getOrDefault("code", "");
        String language = (String) ans.getOrDefault("language", "python");
        if (code == null || code.isBlank()) return 0;

        List<RunCodeRequest.TestCasePayload> cases = (q.getTestCases() != null)
                ? q.getTestCases().stream()
                    .map(tc -> new RunCodeRequest.TestCasePayload(tc.getInput(), tc.getOutput(), tc.getPoints(), tc.isVisible()))
                    .collect(Collectors.toList())
                : List.of();
        if (cases.isEmpty()) return 0;

        List<TestCaseResultDto> results = codeExecutionService.execute(code, language, cases);
        int earned = results.stream().filter(TestCaseResultDto::isPassed).mapToInt(TestCaseResultDto::getEarnedPoints).sum();

        List<Map<String, Object>> testResultsToSave = results.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("input", r.getInput()); m.put("expected", r.getExpected()); m.put("actual", r.getActual());
            m.put("passed", r.isPassed()); m.put("points", r.getPoints()); m.put("earnedPoints", r.getEarnedPoints());
            m.put("executionMs", r.getExecutionMs()); m.put("isVisible", true); m.put("error", r.getError());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> enrichedAns = new LinkedHashMap<>(ans);
        enrichedAns.put("testResults", testResultsToSave);
        answers.put(q.getId(), enrichedAns);

        log.info("Question {} — code scored: {}/{} pts", q.getId(), earned, maxPts);
        return Math.min(earned, maxPts);
    }

    @SuppressWarnings("unchecked")
    private int scoreQcm(Question q, Object rawAnswer, int maxPts) {
        String qType = q.getQuestionType() != null ? q.getQuestionType().name() : null;
        if (qType == null) return 0;
        List<Question.QcmOption> opts = q.getQcmOptions() != null ? q.getQcmOptions() : List.of();

        return switch (qType) {
            case "RADIO" -> {
                String selected = rawAnswer.toString();
                yield opts.stream()
                        .filter(o -> o.getId() == null || selected.equals(o.getId()))
                        .mapToInt(o -> o.getPoints() != null ? o.getPoints() : 0)
                        .findFirst().orElse(0);
            }
            case "CHECKBOX" -> {
                List<String> selected;
                try {
                    selected = objectMapper.readValue(objectMapper.writeValueAsString(rawAnswer), new TypeReference<>() {});
                } catch (Exception e) { yield 0; }
                yield opts.stream()
                        .filter(o -> selected.contains(o.getId()) || selected.contains(o.getText()))
                        .mapToInt(o -> o.getPoints() != null ? o.getPoints() : 0).sum();
            }
            case "LIKERT" -> {
                int val = Integer.parseInt(rawAnswer.toString());
                int idx = val - 1;
                if (q.getLikertPoints() != null && idx >= 0 && idx < q.getLikertPoints().size())
                    yield q.getLikertPoints().get(idx);
                yield 0;
            }
            default -> 0;
        };
    }

    private SimpleQuestionDto mapToTechnicalDto(Question q, Object savedAnswer) {
        List<TestCaseDto> cases = null;
        if (q.getTestCases() != null) {
            cases = q.getTestCases().stream()
                    .map(tc -> TestCaseDto.builder()
                            .input(tc.getInput()).output(tc.getOutput())
                            .points(tc.getPoints() != null ? tc.getPoints() : 10).isVisible(tc.isVisible()).build())
                    .collect(Collectors.toList());
        }

        List<QcmOptionDto> options = null;
        if (q.getQcmOptions() != null) {
            List<Question.QcmOption> shuffled = new ArrayList<>(q.getQcmOptions());
            Collections.shuffle(shuffled);
            final int[] idx = {0};
            options = shuffled.stream()
                    .map(o -> QcmOptionDto.builder().id("opt-" + idx[0]++).text(o.getText())
                            .points(o.getPoints() != null ? o.getPoints() : 0).build())
                    .collect(Collectors.toList());
        }

        return SimpleQuestionDto.builder()
                .id(q.getId()).title(q.getTitle()).statement(q.getStatement())
                .points(q.getPoints() != null ? q.getPoints() : 10)
                .orderIndex(q.getOrderIndex() != null ? q.getOrderIndex() : 0)
                .type(q.getKind() != null ? q.getKind().name() : "QCM")
                .complexity(q.getComplexity()).timeLimit(q.getTimeLimit()).memoryLimit(q.getMemoryLimit())
                .testCases(cases)
                .supportedLangs(q.getSupportedLangs() != null && !q.getSupportedLangs().isEmpty()
                        ? q.getSupportedLangs() : List.of("python", "javascript", "java", "c", "cpp", "go"))
                .selectedLanguage("python")
                .questionType(q.getQuestionType() != null ? q.getQuestionType().name() : null)
                .options(options).likertPoints(q.getLikertPoints())
                .imageUrl(q.getImagePath() != null
                        ? baseUrl + "/api/v1/job-tests/simple-questions/" + q.getId() + "/image" : null)
                .savedAnswer(savedAnswer).build();
    }

    private com.nexgenai.dto.test.QuestionDto mapRhQuestion(Question q) {
        String imageUrl = q.getImagePath() != null
                ? baseUrl + "/api/v1/job-tests/questions/" + q.getId() + "/image" : null;

        List<com.nexgenai.dto.test.OptionDto> options = new ArrayList<>(q.getOptions()).stream()
                .sorted(Comparator.comparingInt(o -> o.getOrderIndex() != null ? o.getOrderIndex() : 0))
                .map(o -> com.nexgenai.dto.test.OptionDto.builder()
                        .id(o.getId()).text(o.getText())
                        .orderIndex(o.getOrderIndex() != null ? o.getOrderIndex() : 0).build())
                .collect(Collectors.toList());

        return com.nexgenai.dto.test.QuestionDto.builder()
                .id(q.getId()).text(q.getText()).imageUrl(imageUrl)
                .kind(q.getKind() != null ? q.getKind().name() : "QCM")
                .questionType(q.getQuestionType() != null ? q.getQuestionType().name() : "RADIO")
                .orderIndex(q.getOrderIndex() != null ? q.getOrderIndex() : 0)
                .options(options).build();
    }

    private AntiCheatRiskLevel computeRiskLevel(List<AntiCheatEventDto> events) {
        if (events == null || events.isEmpty()) return AntiCheatRiskLevel.LOW;
        long tabSwitches = events.stream().filter(e -> "TAB_SWITCH".equals(e.getType())).count();
        long pastes      = events.stream().filter(e -> "PASTE".equals(e.getType())).count();
        long devTools    = events.stream()
                .filter(e -> "DEV_TOOLS".equals(e.getType()) || "DEVTOOLS_ATTEMPT".equals(e.getType())
                          || "DEVTOOLS".equals(e.getType())).count();
        int score = (int)(tabSwitches * 2 + pastes * 3 + devTools * 5);
        if (score >= 10) return AntiCheatRiskLevel.HIGH;
        if (score >= 4)  return AntiCheatRiskLevel.MEDIUM;
        return AntiCheatRiskLevel.LOW;
    }

    @SuppressWarnings("unchecked")
    private int estimateEarnedFromAnswer(Question q, Object rawAnswer) {
        if (rawAnswer == null) return 0;
        if (q.getKind() == Question.QuestionKind.PROBLEM_SOLVING && rawAnswer instanceof Map<?,?> m) {
            Object tcrRaw = ((Map<String,Object>) m).get("testResults");
            if (tcrRaw instanceof List<?> list) {
                return list.stream()
                        .filter(item -> item instanceof Map<?,?> tc && Boolean.TRUE.equals(((Map<?,?>) tc).get("passed")))
                        .mapToInt(item -> {
                            Object ep = ((Map<?,?>) item).get("earnedPoints");
                            return ep instanceof Number n ? n.intValue() : 0;
                        }).sum();
            }
        }
        return 0;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PER-ANSWER EVALUATOR DECISIONS
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public AnswerDecisionResponse setAnswerDecision(String assessmentId, String candidateId,
                                                    String questionId, AnswerDecisionRequest req) {
        TestSession session = testSessionRepository
                .findByCandidateIdAndAssessmentId(candidateId, assessmentId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        Map<String, Object> decisions = parseDecisions(session.getDecisionsJson());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("decision",     req.getDecision());
        entry.put("note",         req.getNote());
        entry.put("manualPoints", req.getManualPoints());
        decisions.put(questionId, entry);

        try { session.setDecisionsJson(objectMapper.writeValueAsString(decisions)); }
        catch (Exception e) { throw new RuntimeException("Could not serialize decisions", e); }
        testSessionRepository.save(session);

        return AnswerDecisionResponse.builder()
                .questionId(questionId).decision(req.getDecision())
                .note(req.getNote()).manualPoints(req.getManualPoints()).build();
    }

    @Transactional
    public void removeAnswerDecision(String assessmentId, String candidateId, String questionId) {
        TestSession session = testSessionRepository
                .findByCandidateIdAndAssessmentId(candidateId, assessmentId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        Map<String, Object> decisions = parseDecisions(session.getDecisionsJson());
        decisions.remove(questionId);
        try { session.setDecisionsJson(objectMapper.writeValueAsString(decisions)); }
        catch (Exception e) { throw new RuntimeException("Could not serialize decisions", e); }
        testSessionRepository.save(session);
    }

    @Transactional(readOnly = true)
    public Map<String, AnswerDecisionResponse> getAnswerDecisions(String assessmentId, String candidateId) {
        TestSession session = testSessionRepository
                .findByCandidateIdAndAssessmentId(candidateId, assessmentId)
                .orElseThrow(() -> new RuntimeException("Session not found"));
        return buildDecisionMap(session.getDecisionsJson());
    }

    @SuppressWarnings("unchecked")
    public Map<String, AnswerDecisionResponse> buildDecisionMap(String decisionsJson) {
        Map<String, Object> raw = parseDecisions(decisionsJson);
        Map<String, AnswerDecisionResponse> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> m) {
                Map<String, Object> d = (Map<String, Object>) m;
                Object mp = d.get("manualPoints");
                result.put(e.getKey(), AnswerDecisionResponse.builder()
                        .questionId(e.getKey())
                        .decision(d.get("decision") != null ? d.get("decision").toString() : null)
                        .note(d.get("note") != null ? d.get("note").toString() : null)
                        .manualPoints(mp instanceof Number n ? n.intValue() : null).build());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSavedAnswers(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private Map<String, Object> parseDecisions(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseAntiCheatLog(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    @SuppressWarnings("unchecked")
    private List<AntiCheatEventDto> parseAntiCheatEvents(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return new ArrayList<>(); }
    }
}
