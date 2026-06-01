package com.nexgenai.service;

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
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the lifecycle of test sessions: start, answer, submit (both technical and RH),
 * and anti-cheat event logging. All candidate answers and events are stored in proper
 * relational tables â€” no JSON columns used.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TestSessionService {

    private static final String CANDIDATE_NOT_FOUND  = "Candidate not found";
    private static final String ASSESSMENT_NOT_FOUND = "Assessment not found: ";
    private static final String SESSION_NOT_FOUND    = "Session not found";
    private static final String LANG_PYTHON          = "python";

    private final TestSessionRepository              testSessionRepository;
    private final AssessmentRepository               assessmentRepository;
    private final QuestionRepository                 questionRepository;
    private final CandidateRepository                candidateRepository;
    private final AntiCheatEventRepository           antiCheatRepository;
    private final TestSubmissionRepository           submissionRepository;
    private final CandidateAnswerRepository          answerRepository;
    private final WorkflowStageRepository            workflowStageRepository;
    private final TestSessionAnswerRepository        sessionAnswerRepository;
    private final TestSessionAnswerDecisionRepository decisionRepository;

    private final CodeExecutionService codeExecutionService;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // START OR GET SESSION (used by CandidateController)
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Transactional
    public Map<String, String> startOrGetSession(String assessmentId, String email) {
        Candidate candidate = candidateRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException(CANDIDATE_NOT_FOUND));
        Assessment assessment = assessmentRepository.findByIdWithThemes(assessmentId)
                .orElseThrow(() -> new IllegalStateException(ASSESSMENT_NOT_FOUND + assessmentId));

        TestSession session = testSessionRepository
                .findByCandidateIdAndAssessmentId(candidate.getId(), assessmentId)
                .orElseGet(() -> testSessionRepository.save(TestSession.builder()
                        .candidate(candidate).assessment(assessment)
                        .type(assessment.getType())
                        .status(TestSession.SessionStatus.IN_PROGRESS)
                        .startedAt(LocalDateTime.now()).build()));

        return Map.of(
                "sessionId", session.getId(),
                "status",    session.getStatus().name()
        );
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // TECHNICAL SESSION
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Transactional
    public TechnicalSessionDto startTechnicalSession(String assessmentId, String existingSessionId, String email) {
        Candidate candidate = candidateRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException(CANDIDATE_NOT_FOUND));
        Assessment assessment = assessmentRepository.findByIdWithThemes(assessmentId)
                .orElseThrow(() -> new IllegalStateException(ASSESSMENT_NOT_FOUND + assessmentId));

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
            throw new IllegalStateException("Test already submitted");

        Map<String, TestSessionAnswer> savedByQuestion = sessionAnswerRepository
                .findBySessionId(session.getId()).stream()
                .collect(Collectors.toMap(TestSessionAnswer::getQuestionId, a -> a));

        List<SimpleQuestionDto> questions = assessment.getThemes().stream()
                .flatMap(theme -> questionRepository.findByThemeIdOrderByOrderIndex(theme.getId()).stream())
                .map(q -> mapToTechnicalDto(q, savedByQuestion.get(q.getId())))
                .toList();

        int timeLimitSeconds = computeTimeLimit(questions);
        session.setTimeLimitSeconds(timeLimitSeconds);
        testSessionRepository.save(session);

        return TechnicalSessionDto.builder()
                .sessionId(session.getId()).testId(assessmentId)
                .testName(assessment.getName()).jobTitle(assessment.getJob().getTitle())
                .timeLimitSeconds(timeLimitSeconds).questions(questions).build();
    }

    public List<TestCaseResultDto> runCode(RunCodeRequest req) {
        return codeExecutionService.execute(req.getCode(), req.getLanguage(), req.getTestCases());
    }

    @Transactional
    public void saveTechnicalAnswer(String sessionId, String questionId, Object answer, String email) {
        TestSession session = testSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException(SESSION_NOT_FOUND));
        if (session.getStatus() == TestSession.SessionStatus.COMPLETED) return;

        persistAnswer(session, questionId, answer);
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public SubmitResultDto submitTechnicalTest(String sessionId, SubmitTestRequest req, String email) {
        TestSession session = testSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException(SESSION_NOT_FOUND));
        if (session.getStatus() == TestSession.SessionStatus.COMPLETED)
            throw new IllegalStateException("Already submitted");

        // Persist any answers sent in the final submission payload
        persistFinalAnswers(session, req.getAnswers());

        // Persist anti-cheat events
        persistAntiCheatLog(sessionId, session.getAssessment(), req.getAntiCheatLog());

        // Load all saved answers for scoring
        Map<String, TestSessionAnswer> answersByQuestion = sessionAnswerRepository
                .findBySessionId(sessionId).stream()
                .collect(Collectors.toMap(TestSessionAnswer::getQuestionId, a -> a));

        Assessment assessment = session.getAssessment();
        List<QuestionResultDto> questionResults = new ArrayList<>();
        int totalPoints = 0;
        int earnedPoints = 0;

        for (TestTheme theme : assessment.getThemes()) {
            List<Question> questions = questionRepository.findByThemeIdOrderByOrderIndex(theme.getId());
            for (Question q : questions) {
                int maxPts = q.getPoints() != null ? q.getPoints() : 10;
                totalPoints += maxPts;
                TestSessionAnswer saved = answersByQuestion.get(q.getId());
                int pts = scoreQuestion(q, saved, maxPts);
                earnedPoints += pts;
                questionResults.add(QuestionResultDto.builder()
                        .questionId(q.getId()).title(q.getTitle())
                        .type(q.getKind() != null ? q.getKind().name() : "QCM")
                        .earnedPoints(pts).maxPoints(maxPts).build());
            }
        }

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

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // RH SESSION
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Transactional
    public com.nexgenai.dto.test.TestSessionDto getRhTestSession(String assessmentId, String sessionId, String email) {
        Candidate candidate = candidateRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException(CANDIDATE_NOT_FOUND));
        Assessment assessment = assessmentRepository.findByIdWithThemes(assessmentId)
                .orElseThrow(() -> new IllegalStateException(ASSESSMENT_NOT_FOUND + assessmentId));

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

        int timeLimitMinutes = resolveRhTimeLimit(assessment);
        int timeLimitSeconds = timeLimitMinutes * 60;
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
                .orElseThrow(() -> new IllegalStateException(SESSION_NOT_FOUND + ": " + sessionId));
        if (session.getStatus() == TestSession.SessionStatus.COMPLETED)
            return session.getScore() != null ? session.getScore() : 0;

        Map<String, List<String>> answers = new HashMap<>();
        for (TestSessionAnswer a : sessionAnswerRepository.findBySessionId(sessionId)) {
            answers.put(a.getQuestionId(), a.getSelectedOptionIds());
        }

        Assessment assessment = assessmentRepository.findByIdWithThemes(session.getAssessment().getId())
                .orElseThrow(() -> new IllegalStateException(ASSESSMENT_NOT_FOUND + "unknown"));

        int[] totals = computeRhScore(assessment, answers);
        int totalPoints  = totals[0];
        int earnedPoints = totals[1];

        int score = totalPoints > 0 ? Math.round((float) earnedPoints / totalPoints * 100) : 0;
        session.setScore(score);
        session.setStatus(TestSession.SessionStatus.COMPLETED);
        session.setCompletedAt(LocalDateTime.now());
        testSessionRepository.save(session);

        log.info("RH test {} submitted by {} â€” Score: {}/100", sessionId, email, score);
        return score;
    }

    @Transactional(readOnly = true)
    public Map<String, List<String>> getRhSavedAnswers(String sessionId, String email) {
        Map<String, List<String>> result = new HashMap<>();
        for (TestSessionAnswer a : sessionAnswerRepository.findBySessionId(sessionId)) {
            result.put(a.getQuestionId(), a.getSelectedOptionIds());
        }
        return result;
    }

    @Transactional
    public void saveRhAnswer(com.nexgenai.dto.test.SaveAnswerRequest req, String email) {
        TestSession session = testSessionRepository.findById(req.getSessionId())
                .orElseThrow(() -> new IllegalStateException(SESSION_NOT_FOUND + ": " + req.getSessionId()));
        if (session.getStatus() == TestSession.SessionStatus.COMPLETED)
            throw new IllegalStateException("Test already submitted");

        TestSessionAnswer answer = sessionAnswerRepository
                .findBySessionIdAndQuestionId(session.getId(), req.getQuestionId())
                .orElseGet(() -> TestSessionAnswer.builder()
                        .session(session).questionId(req.getQuestionId()).build());

        answer.setSelectedOptionIds(req.getOptionIds() != null ? req.getOptionIds() : List.of());
        sessionAnswerRepository.save(answer);
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // ANTI-CHEAT
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Transactional
    public void logAntiCheatEvent(String sessionId, AntiCheatEventDto event, String email) {
        TestSession session = testSessionRepository.findById(sessionId).orElse(null);
        if (session == null) return;

        antiCheatRepository.save(AntiCheatEvent.builder()
                .testId(session.getAssessment().getId())
                .sessionId(sessionId)
                .type(event.getType())
                .detail(event.getDetail())
                .questionIndex(event.getQuestionIndex())
                .occurredAt(parseTimestamp(event.getTimestamp()))
                .build());
    }

    @Transactional(readOnly = true)
    public AntiCheatReportDto getAntiCheatReport(String sessionId) {
        TestSession session = testSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException(SESSION_NOT_FOUND));

        List<AntiCheatEvent> rawEvents = antiCheatRepository.findBySessionIdOrderByOccurredAtAsc(sessionId);

        List<AntiCheatEventDto> events = rawEvents.stream()
                .map(e -> {
                    AntiCheatEventDto dto = new AntiCheatEventDto();
                    dto.setType(e.getType());
                    dto.setDetail(e.getDetail());
                    dto.setQuestionIndex(e.getQuestionIndex());
                    dto.setTimestamp(e.getOccurredAt() != null ? e.getOccurredAt().toString() : null);
                    return dto;
                }).toList();

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

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // PER-ANSWER EVALUATOR DECISIONS
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Transactional
    public AnswerDecisionResponse setAnswerDecision(String assessmentId, String candidateId,
                                                    String questionId, AnswerDecisionRequest req) {
        TestSession session = testSessionRepository
                .findByCandidateIdAndAssessmentId(candidateId, assessmentId)
                .orElseThrow(() -> new IllegalStateException(SESSION_NOT_FOUND));

        TestSessionAnswerDecision decision = decisionRepository
                .findBySessionIdAndQuestionId(session.getId(), questionId)
                .orElseGet(() -> TestSessionAnswerDecision.builder()
                        .session(session).questionId(questionId).build());

        decision.setDecision(req.getDecision());
        decision.setNote(req.getNote());
        decision.setManualPoints(req.getManualPoints());
        decisionRepository.save(decision);

        return AnswerDecisionResponse.builder()
                .questionId(questionId).decision(req.getDecision())
                .note(req.getNote()).manualPoints(req.getManualPoints()).build();
    }

    @Transactional
    public void removeAnswerDecision(String assessmentId, String candidateId, String questionId) {
        TestSession session = testSessionRepository
                .findByCandidateIdAndAssessmentId(candidateId, assessmentId)
                .orElseThrow(() -> new IllegalStateException(SESSION_NOT_FOUND));
        decisionRepository.deleteBySessionIdAndQuestionId(session.getId(), questionId);
    }

    @Transactional(readOnly = true)
    public Map<String, AnswerDecisionResponse> getAnswerDecisions(String assessmentId, String candidateId) {
        TestSession session = testSessionRepository
                .findByCandidateIdAndAssessmentId(candidateId, assessmentId)
                .orElseThrow(() -> new IllegalStateException(SESSION_NOT_FOUND));
        return buildDecisionMap(session.getId());
    }

    public Map<String, AnswerDecisionResponse> buildDecisionMap(String sessionId) {
        Map<String, AnswerDecisionResponse> result = new LinkedHashMap<>();
        for (TestSessionAnswerDecision d : decisionRepository.findBySessionId(sessionId)) {
            result.put(d.getQuestionId(), AnswerDecisionResponse.builder()
                    .questionId(d.getQuestionId())
                    .decision(d.getDecision())
                    .note(d.getNote())
                    .manualPoints(d.getManualPoints()).build());
        }
        return result;
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // PRIVATE HELPERS
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private TestSession createTechnicalSession(Candidate candidate, Assessment assessment) {
        return testSessionRepository.save(TestSession.builder()
                .candidate(candidate).assessment(assessment).type(AssessmentType.TECHNICAL)
                .status(TestSession.SessionStatus.IN_PROGRESS).startedAt(LocalDateTime.now()).build());
    }

    private TestSession createRhSession(Candidate candidate, Assessment assessment) {
        return testSessionRepository.save(TestSession.builder()
                .candidate(candidate).assessment(assessment).type(AssessmentType.RH)
                .status(TestSession.SessionStatus.IN_PROGRESS).startedAt(LocalDateTime.now()).build());
    }

    @SuppressWarnings("unchecked")
    private int[] computeRhScore(Assessment assessment, Map<String, List<String>> answers) {
        int totalPoints = 0;
        int earnedPoints = 0;
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
        return new int[]{totalPoints, earnedPoints};
    }

    private void persistFinalAnswers(TestSession session, Map<String, Object> answers) {
        if (answers == null) return;
        for (Map.Entry<String, Object> entry : answers.entrySet()) {
            persistAnswer(session, entry.getKey(), entry.getValue());
        }
    }

    private void persistAntiCheatLog(String sessionId, Assessment assessment,
                                     List<AntiCheatEventDto> log) {
        if (log == null) return;
        for (AntiCheatEventDto evt : log) {
            antiCheatRepository.save(AntiCheatEvent.builder()
                    .testId(assessment.getId())
                    .sessionId(sessionId)
                    .type(evt.getType())
                    .detail(evt.getDetail())
                    .questionIndex(evt.getQuestionIndex())
                    .occurredAt(parseTimestamp(evt.getTimestamp()))
                    .build());
        }
    }

    private void persistAnswer(TestSession session, String questionId, Object answer) {
        if (answer == null) return;
        TestSessionAnswer entity = sessionAnswerRepository
                .findBySessionIdAndQuestionId(session.getId(), questionId)
                .orElseGet(() -> TestSessionAnswer.builder()
                        .session(session).questionId(questionId).build());

        if (answer instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            entity.setSubmittedCode((String) m.get("code"));
            entity.setSubmittedLanguage((String) m.get("language"));
        } else if (answer instanceof List<?> list) {
            entity.setSelectedOptionIds(list.stream().map(Object::toString).toList());
        } else if (answer instanceof Number num) {
            entity.setLikertValue(num.intValue());
        } else {
            entity.setSelectedOptionIds(List.of(answer.toString()));
        }
        sessionAnswerRepository.save(entity);
    }

    private int scoreQuestion(Question q, TestSessionAnswer saved, int maxPts) {
        if (saved == null) return 0;
        try {
            if (q.getKind() == Question.QuestionKind.PROBLEM_SOLVING)
                return scoreProblemSolving(q, saved, maxPts);
            return scoreQcm(q, saved);
        } catch (Exception e) {
            log.warn("Error scoring question {}: {}", q.getId(), e.getMessage());
            return 0;
        }
    }

    private int scoreProblemSolving(Question q, TestSessionAnswer saved, int maxPts) {
        String code     = saved.getSubmittedCode();
        String language = saved.getSubmittedLanguage();
        if (code == null || code.isBlank()) return 0;
        if (language == null) language = LANG_PYTHON;

        List<RunCodeRequest.TestCasePayload> cases = (q.getTestCases() != null)
                ? q.getTestCases().stream()
                    .map(tc -> new RunCodeRequest.TestCasePayload(tc.getInput(), tc.getOutput(),
                                                                  tc.getPoints(), tc.isVisible()))
                    .toList()
                : List.of();
        if (cases.isEmpty()) return 0;

        List<TestCaseResultDto> results = codeExecutionService.execute(code, language, cases);
        int earned = results.stream().filter(TestCaseResultDto::isPassed)
                .mapToInt(TestCaseResultDto::getEarnedPoints).sum();

        // Persist test-case execution results as entities
        saved.getTestCaseResults().clear();
        for (int i = 0; i < results.size(); i++) {
            TestCaseResultDto r = results.get(i);
            saved.getTestCaseResults().add(TestCaseResult.builder()
                    .sessionAnswer(saved).caseIndex(i)
                    .input(r.getInput()).expectedOutput(r.getExpected()).actualOutput(r.getActual())
                    .passed(r.isPassed()).points(r.getPoints()).earnedPoints(r.getEarnedPoints())
                    .executionMs(r.getExecutionMs()).isVisible(true).error(r.getError())
                    .build());
        }
        sessionAnswerRepository.save(saved);

        log.info("Question {} â€” code scored: {}/{} pts", q.getId(), earned, maxPts);
        return Math.min(earned, maxPts);
    }

    private int scoreQcm(Question q, TestSessionAnswer saved) {
        String qType = q.getQuestionType() != null ? q.getQuestionType().name() : null;
        if (qType == null) return 0;
        List<Question.QcmOption> opts = q.getQcmOptions() != null ? q.getQcmOptions() : List.of();

        return switch (qType) {
            case "RADIO" -> {
                List<String> sel = saved.getSelectedOptionIds();
                String selected = (sel != null && !sel.isEmpty()) ? sel.get(0) : "";
                yield opts.stream()
                        .filter(o -> o.getId() != null && selected.equals(o.getId()))
                        .mapToInt(o -> o.getPoints() != null ? o.getPoints() : 0)
                        .findFirst().orElse(0);
            }
            case "CHECKBOX" -> {
                List<String> selected = saved.getSelectedOptionIds() != null ? saved.getSelectedOptionIds() : List.of();
                yield opts.stream()
                        .filter(o -> selected.contains(o.getId()) || selected.contains(o.getText()))
                        .mapToInt(o -> o.getPoints() != null ? o.getPoints() : 0).sum();
            }
            case "LIKERT" -> {
                Integer val = saved.getLikertValue();
                if (val == null) yield 0;
                int idx = val - 1;
                if (q.getLikertPoints() != null && idx >= 0 && idx < q.getLikertPoints().size())
                    yield q.getLikertPoints().get(idx);
                yield 0;
            }
            default -> 0;
        };
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
                .toList();
    }

    private List<AntiCheatReportDto.QuestionResult> buildAntiCheatQuestionResults(TestSession session) {
        Assessment assessment = session.getAssessment();
        Map<String, TestSessionAnswer> byQuestion = sessionAnswerRepository
                .findBySessionId(session.getId()).stream()
                .collect(Collectors.toMap(TestSessionAnswer::getQuestionId, a -> a));

        List<AntiCheatReportDto.QuestionResult> results = new ArrayList<>();

        for (TestTheme theme : assessment.getThemes()) {
            List<Question> questions = questionRepository.findByThemeIdOrderByOrderIndex(theme.getId());
            if (questions.isEmpty() && !byQuestion.isEmpty())
                questions = questionRepository.findByIdIn(new ArrayList<>(byQuestion.keySet()));

            for (Question q : questions) {
                addAntiCheatResult(q, byQuestion, results);
            }
        }
        return results;
    }

    private void addAntiCheatResult(Question q, Map<String, TestSessionAnswer> byQuestion,
            List<AntiCheatReportDto.QuestionResult> results) {
        int maxPts = q.getPoints() != null ? q.getPoints() : 0;
        TestSessionAnswer saved = byQuestion.get(q.getId());
        int earned = estimateEarned(q, saved);
        String title = q.getTitle() != null ? q.getTitle() : "Question";
        String type  = q.getKind()  != null ? q.getKind().name() : "QCM";
        results.add(AntiCheatReportDto.QuestionResult.builder()
            .questionId(q.getId()).title(title).type(type)
            .earnedPoints(earned).maxPoints(maxPts).build());
    }

    private int estimateEarned(Question q, TestSessionAnswer saved) {
        if (saved == null) return 0;
        if (q.getKind() == Question.QuestionKind.PROBLEM_SOLVING) {
            return saved.getTestCaseResults().stream()
                    .filter(TestCaseResult::isPassed)
                    .mapToInt(TestCaseResult::getEarnedPoints).sum();
        }
        return 0;
    }

    private SimpleQuestionDto mapToTechnicalDto(Question q, TestSessionAnswer savedAnswer) {
        List<TestCaseDto> cases = null;
        if (q.getTestCases() != null) {
            cases = q.getTestCases().stream()
                    .map(tc -> TestCaseDto.builder()
                            .input(tc.getInput()).output(tc.getOutput())
                            .points(tc.getPoints() != null ? tc.getPoints() : 10).isVisible(tc.isVisible()).build())
                    .toList();
        }

        List<QcmOptionDto> options = null;
        if (q.getQcmOptions() != null) {
            List<Question.QcmOption> shuffled = new ArrayList<>(q.getQcmOptions());
            Collections.shuffle(shuffled);
            final int[] idx = {0};
            options = shuffled.stream()
                    .map(o -> QcmOptionDto.builder().id("opt-" + idx[0]++).text(o.getText())
                            .points(o.getPoints() != null ? o.getPoints() : 0).build())
                    .toList();
        }

        Object savedValue = null;
        if (savedAnswer != null) {
            if (savedAnswer.getSubmittedCode() != null) {
                Map<String, Object> codeMap = new LinkedHashMap<>();
                codeMap.put("code", savedAnswer.getSubmittedCode());
                codeMap.put("language", savedAnswer.getSubmittedLanguage());
                savedValue = codeMap;
            } else if (savedAnswer.getLikertValue() != null) {
                savedValue = savedAnswer.getLikertValue();
            } else if (!savedAnswer.getSelectedOptionIds().isEmpty()) {
                savedValue = savedAnswer.getSelectedOptionIds();
            }
        }

        return SimpleQuestionDto.builder()
                .id(q.getId()).title(q.getTitle()).statement(q.getStatement())
                .points(q.getPoints() != null ? q.getPoints() : 10)
                .orderIndex(q.getOrderIndex() != null ? q.getOrderIndex() : 0)
                .type(q.getKind() != null ? q.getKind().name() : "QCM")
                .complexity(q.getComplexity()).timeLimit(q.getTimeLimit()).memoryLimit(q.getMemoryLimit())
                .testCases(cases)
                .supportedLangs(q.getSupportedLangs() != null && !q.getSupportedLangs().isEmpty()
                        ? q.getSupportedLangs() : List.of(LANG_PYTHON, "javascript", "java", "c", "cpp", "go"))
                .selectedLanguage(LANG_PYTHON)
                .questionType(q.getQuestionType() != null ? q.getQuestionType().name() : null)
                .options(options).likertPoints(q.getLikertPoints())
                .imageUrl(q.getImagePath() != null
                        ? baseUrl + "/api/v1/job-tests/simple-questions/" + q.getId() + "/image" : null)
                .savedAnswer(savedValue).build();
    }

    private com.nexgenai.dto.test.QuestionDto mapRhQuestion(Question q) {
        String imageUrl = q.getImagePath() != null
                ? baseUrl + "/api/v1/job-tests/questions/" + q.getId() + "/image" : null;

        List<com.nexgenai.dto.test.OptionDto> options = new ArrayList<>(q.getOptions()).stream()
                .sorted(Comparator.comparingInt(o -> o.getOrderIndex() != null ? o.getOrderIndex() : 0))
                .map(o -> com.nexgenai.dto.test.OptionDto.builder()
                        .id(o.getId()).text(o.getText())
                        .orderIndex(o.getOrderIndex() != null ? o.getOrderIndex() : 0).build())
                .toList();

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

    private LocalDateTime parseTimestamp(String ts) {
        if (ts == null || ts.isBlank()) return LocalDateTime.now();
        try { return OffsetDateTime.parse(ts).toLocalDateTime(); }
        catch (DateTimeParseException e) {
            try { return LocalDateTime.parse(ts); }
            catch (Exception e2) { return LocalDateTime.now(); }
        }
    }
}
