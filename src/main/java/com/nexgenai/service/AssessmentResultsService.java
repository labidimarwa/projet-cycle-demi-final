package com.nexgenai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexgenai.dto.hr.AnswerDecisionResponse;
import com.nexgenai.dto.jobtest.JobTestDtos.*;
import com.nexgenai.model.*;
import com.nexgenai.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Provides read-only views of test results and candidate rankings for assessments.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssessmentResultsService {

    private final TestSessionRepository      testSessionRepository;
    private final AssessmentRepository       assessmentRepository;
    private final QuestionRepository         questionRepository;
    private final QuestionOptionRepository   optionRepository;
    private final ThemeModelRepository       themeModelRepository;
    private final CandidateRepository        candidateRepository;
    private final AntiCheatEventRepository   antiCheatRepository;
    private final TestThemeRepository        themeRepository;

    private final ObjectMapper objectMapper;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    // ══════════════════════════════════════════════════════════════════════════
    // CANDIDATES LIST FOR ASSESSMENT
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public TestCandidatesResponse getCandidatesForTest(String assessmentId) {
        Assessment assessment = findAssessment(assessmentId);
        List<TestSession> sessions = testSessionRepository.findByAssessmentId(assessmentId);

        List<TestSession> completed = sessions.stream()
            .filter(s -> s.getStatus() == TestSession.SessionStatus.COMPLETED)
            .sorted(Comparator.comparingDouble(s -> -safeScore(s)))
            .collect(Collectors.toList());

        List<CandidateSummaryResponse> summaries = new ArrayList<>();
        for (TestSession session : sessions) {
            Candidate candidate  = session.getCandidate();
            boolean isCompleted  = session.getStatus() == TestSession.SessionStatus.COMPLETED;
            Integer rank         = isCompleted ? completed.indexOf(session) + 1 : null;

            summaries.add(CandidateSummaryResponse.builder()
                .candidateId(candidate.getId())
                .candidateName(candidate.getFirstName() + " " + candidate.getLastName())
                .candidateEmail(candidate.getEmail())
                .candidateAvatar(null)
                .status(session.getStatus().name())
                .completedAt(session.getCompletedAt() != null ? session.getCompletedAt().toString() : null)
                .percentage(isCompleted ? (double) safeScore(session) : null)
                .totalScore(isCompleted ? session.getEarnedPoints() : null)
                .maxScore(isCompleted ? session.getTotalPoints() : null)
                .rank(rank).build());
        }

        long completedCount  = sessions.stream().filter(s -> s.getStatus() == TestSession.SessionStatus.COMPLETED).count();
        long inProgressCount = sessions.stream().filter(s -> s.getStatus() == TestSession.SessionStatus.IN_PROGRESS).count();
        long notStarted      = sessions.stream().filter(s -> s.getStatus() == TestSession.SessionStatus.PENDING
                                                          || s.getStatus() == TestSession.SessionStatus.NOT_STARTED).count();
        double avgScore = completed.isEmpty() ? 0.0
            : completed.stream().mapToDouble(this::safeScore).average().orElse(0.0);

        return TestCandidatesResponse.builder()
            .testId(assessment.getId()).testName(assessment.getName())
            .jobTitle(assessment.getJob().getTitle()).department(assessment.getJob().getDepartment())
            .totalInvited(sessions.size()).completed((int) completedCount)
            .inProgress((int) inProgressCount).notStarted((int) notStarted)
            .avgScore(Math.round(avgScore * 10.0) / 10.0)
            .candidates(summaries).build();
    }

    @Transactional(readOnly = true)
    public TestCandidatesResponse getRhCandidatesForTest(String assessmentId) {
        return getCandidatesForTest(assessmentId);
    }

    @Transactional(readOnly = true)
    public TestCandidatesResponse getTechCandidatesForTest(String assessmentId) {
        return getCandidatesForTest(assessmentId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RH CANDIDATE RESULT
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public CandidateTestResultResponse getRhCandidateResult(String assessmentId, String candidateId) {
        Assessment assessment = findAssessment(assessmentId);
        TestSession session = testSessionRepository
            .findByCandidateIdAndAssessmentId(candidateId, assessmentId)
            .orElseThrow(() -> new RuntimeException("RH session not found"));
        Candidate candidate = session.getCandidate();

        Map<String, Object> rawAnswers = parseSavedAnswers(session.getAnswersJson());
        Map<String, String> answerMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : rawAnswers.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof List<?> list && !list.isEmpty())
                answerMap.put(entry.getKey(), list.get(0).toString());
            else if (val instanceof String s)
                answerMap.put(entry.getKey(), s);
        }

        List<ThemeResultResponse>  themeResults    = new ArrayList<>();
        List<ModelAnswersResponse> modelAnswersList = new ArrayList<>();

        for (TestTheme theme : assessment.getThemes()) {
            List<ThemeModelResultResponse> modelResults = new ArrayList<>();
            int themeTotal = 0, themeMax = 0;

            for (ThemeModel tm : theme.getThemeModels()) {
                Map<String, ModelDimension> dimById = tm.getModel().getDimensions().stream()
                    .collect(Collectors.toMap(ModelDimension::getId, d -> d));

                Map<String, Integer> dimScores = new HashMap<>();
                Map<String, Integer> dimMaxMap = new HashMap<>();
                for (ModelDimension d : tm.getModel().getDimensions()) {
                    dimScores.put(d.getId(), 0); dimMaxMap.put(d.getId(), 0);
                }

                int modelEarned = 0, modelMaxPts = 0, answered = 0;
                List<QuestionAnswerResponse> questionDetails = new ArrayList<>();

                for (Question q : tm.getQuestions()) {
                    String selectedOptionId = answerMap.get(q.getId());
                    int qEarned = 0;
                    List<QcmOptionAnswerResponse> optionResponses = new ArrayList<>();

                    for (QuestionOption opt : q.getOptions()) {
                        boolean selected = opt.getId() != null && opt.getId().equals(selectedOptionId);
                        int pts = opt.getPoints() != null ? opt.getPoints() : 0;

                        if (pts > 0 && opt.getDimensionId() != null) {
                            dimMaxMap.merge(opt.getDimensionId(), pts, Integer::sum);
                            modelMaxPts += pts;
                        }
                        if (selected) {
                            if (opt.getDimensionId() != null) dimScores.merge(opt.getDimensionId(), pts, Integer::sum);
                            modelEarned += pts; qEarned += pts; answered++;
                        }

                        ModelDimension dimObj = opt.getDimensionId() != null ? dimById.get(opt.getDimensionId()) : null;
                        optionResponses.add(QcmOptionAnswerResponse.builder()
                            .id(opt.getId()).text(opt.getText()).isCorrect(false).points(pts)
                            .selected(selected).dimensionId(opt.getDimensionId())
                            .dimensionName(dimObj != null ? dimObj.getName() : null)
                            .optionText(opt.getText()).build());
                    }

                    int maxPts = q.getOptions().stream().mapToInt(o -> o.getPoints() != null ? o.getPoints() : 0).sum();
                    String imageUrl = q.getImagePath() != null
                        ? baseUrl + "/api/v1/job-tests/questions/" + q.getId() + "/image" : null;

                    questionDetails.add(QuestionAnswerResponse.builder()
                        .questionId(q.getId()).title("").statement(q.getText()).type("QCM")
                        .questionType("RADIO").points(maxPts).earnedPoints(qEarned)
                        .orderIndex(q.getOrderIndex() != null ? q.getOrderIndex() : 0)
                        .imageUrl(imageUrl).options(optionResponses)
                        .likertPoints(List.of()).testCases(List.of())
                        .answerDecision(null).answerNote(null).manualPoints(null).build());
                }

                themeTotal += modelEarned; themeMax += modelMaxPts;

                modelAnswersList.add(ModelAnswersResponse.builder()
                    .themeModelId(tm.getId()).modelName(tm.getModel().getName())
                    .questions(questionDetails).build());

                List<DimensionScoreResponse> dimResponses = tm.getModel().getDimensions().stream()
                    .sorted(Comparator.comparingInt(d -> d.getOrderIndex() != null ? d.getOrderIndex() : 0))
                    .map(d -> {
                        int ds = dimScores.getOrDefault(d.getId(), 0);
                        int dm = dimMaxMap.getOrDefault(d.getId(), 0);
                        double pct = dm > 0 ? Math.round((double) ds / dm * 1000.0) / 10.0 : 0.0;
                        return DimensionScoreResponse.builder()
                            .dimensionId(d.getId()).dimensionName(d.getName())
                            .dimensionCode(d.getCode()).color(d.getColor())
                            .score(ds).maxScore(dm).percentage(pct).build();
                    }).collect(Collectors.toList());

                double modelPct = modelMaxPts > 0
                    ? Math.round((double) modelEarned / modelMaxPts * 1000.0) / 10.0 : 0.0;

                modelResults.add(ThemeModelResultResponse.builder()
                    .themeModelId(tm.getId()).modelId(tm.getModel().getId())
                    .modelName(tm.getModel().getName()).scoringType(tm.getModel().getScoringType().name())
                    .weight(tm.getWeight() != null ? tm.getWeight() : 50)
                    .totalScore(modelEarned).maxScore(modelMaxPts).percentage(modelPct)
                    .questionsCount(tm.getQuestions().size()).answeredCount(answered)
                    .dimensions(dimResponses).build());
            }

            double themePct = themeMax > 0 ? Math.round((double) themeTotal / themeMax * 1000.0) / 10.0 : 0.0;
            themeResults.add(ThemeResultResponse.builder()
                .themeId(theme.getId()).themeName(theme.getName())
                .themeCategory(theme.getCategory() != null ? theme.getCategory().name() : "CUSTOM")
                .totalScore(themeTotal).maxScore(themeMax).percentage(themePct)
                .models(modelResults).build());
        }

        List<TestSession> allCompleted = completedSorted(assessmentId);
        int rank = allCompleted.indexOf(session) + 1;
        Integer durationMinutes = computeDuration(session);
        int score = session.getScore() != null ? session.getScore() : 0;

        return CandidateTestResultResponse.builder()
            .submissionId(session.getId()).candidateId(candidate.getId())
            .candidateName(candidate.getFirstName() + " " + candidate.getLastName())
            .candidateEmail(candidate.getEmail()).jobTitle(assessment.getJob().getTitle())
            .testName(assessment.getName()).testId(assessmentId)
            .status(session.getStatus().name())
            .startedAt(session.getStartedAt() != null ? session.getStartedAt().toString() : null)
            .completedAt(session.getCompletedAt() != null ? session.getCompletedAt().toString() : null)
            .durationMinutes(durationMinutes).totalScore(score).maxScore(100).percentage((double) score)
            .rank(rank > 0 ? rank : null).themes(themeResults).modelAnswers(modelAnswersList).build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TECHNICAL CANDIDATE RESULT
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public CandidateTestResultResponse getTechCandidateResult(String assessmentId, String candidateId) {
        Assessment assessment = findAssessment(assessmentId);
        TestSession session = testSessionRepository
            .findByCandidateIdAndAssessmentId(candidateId, assessmentId)
            .orElseThrow(() -> new RuntimeException("Technical session not found"));

        Candidate candidate = session.getCandidate();
        int totalPts  = session.getTotalPoints()  != null ? session.getTotalPoints()  : 0;
        int earnedPts = session.getEarnedPoints() != null ? session.getEarnedPoints() : 0;
        double pct    = totalPts > 0 ? Math.round((double) earnedPts / totalPts * 1000.0) / 10.0 : 0.0;

        List<TestSession> allCompleted = completedSorted(assessmentId);
        int rank = allCompleted.indexOf(session) + 1;

        return CandidateTestResultResponse.builder()
            .submissionId(session.getId()).candidateId(candidate.getId())
            .candidateName(candidate.getFirstName() + " " + candidate.getLastName())
            .candidateEmail(candidate.getEmail()).jobTitle(assessment.getJob().getTitle())
            .testName(assessment.getName()).testId(assessmentId)
            .status(session.getStatus().name())
            .startedAt(session.getStartedAt() != null ? session.getStartedAt().toString() : null)
            .completedAt(session.getCompletedAt() != null ? session.getCompletedAt().toString() : null)
            .durationMinutes(computeDuration(session))
            .totalScore(earnedPts).maxScore(totalPts).percentage(pct)
            .rank(rank > 0 ? rank : null).themes(List.of()).modelAnswers(List.of()).build();
    }

    @Transactional(readOnly = true)
    public CandidateTestResultResponse getCandidateResult(String assessmentId, String candidateId) {
        return getTechCandidateResult(assessmentId, candidateId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CANDIDATE FULL RESULT
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public CandidateFullResultResponse getCandidateFullResult(String assessmentId, String candidateId) {
        log.info("getCandidateFullResult — assessmentId={} candidateId={}", assessmentId, candidateId);
        Assessment assessment = findAssessment(assessmentId);

        TestSession session = testSessionRepository
            .findByCandidateIdAndAssessmentId(candidateId, assessmentId)
            .orElseThrow(() -> new RuntimeException(
                "Session not found for assessmentId=" + assessmentId + " candidateId=" + candidateId));

        Map<String, Object> answersMap = parseSavedAnswers(session.getAnswersJson());
        Map<String, AnswerDecisionResponse> decisionsMap = parseDecisions(session.getDecisionsJson());
        List<ThemeAnswersResponse> themeAnswers = new ArrayList<>();
        int totalEarned = 0, totalMax = 0;

        if (!answersMap.isEmpty()) {
            List<Question> questions = questionRepository.findByIdIn(new ArrayList<>(answersMap.keySet()));

            Map<String, List<Question>> byTheme = new LinkedHashMap<>();
            for (Question q : questions) {
                String tid = q.getTheme() != null ? q.getTheme().getId() : "_root";
                byTheme.computeIfAbsent(tid, k -> new ArrayList<>()).add(q);
            }

            if (byTheme.isEmpty()) {
                int sessionEarned = session.getEarnedPoints() != null ? session.getEarnedPoints() : 0;
                int sessionTotal  = session.getTotalPoints()  != null ? session.getTotalPoints()  : 0;
                double pct = sessionTotal > 0
                    ? Math.round((double) sessionEarned / sessionTotal * 1000.0) / 10.0 : 0.0;
                themeAnswers.add(ThemeAnswersResponse.builder()
                    .themeId(assessment.getId()).themeName(assessment.getName())
                    .themeCategory("LOGIC").totalScore(sessionEarned).maxScore(sessionTotal).percentage(pct)
                    .questions(Collections.emptyList()).build());
                totalEarned = sessionEarned; totalMax = sessionTotal;
            }

            for (Map.Entry<String, List<Question>> entry : byTheme.entrySet()) {
                List<Question> themeQs = entry.getValue();
                themeQs.sort(Comparator.comparingInt(q -> q.getOrderIndex() != null ? q.getOrderIndex() : 0));

                String themeName = assessment.getName(), themeCategory = "LOGIC", themeId = entry.getKey();
                if (!themeQs.isEmpty() && themeQs.get(0).getTheme() != null) {
                    TestTheme th = themeQs.get(0).getTheme();
                    if (th.getName()     != null) themeName     = th.getName();
                    if (th.getCategory() != null) themeCategory = th.getCategory().name();
                    themeId = th.getId();
                }

                List<QuestionAnswerResponse> qResponses = new ArrayList<>();
                int themeEarned = 0, themeMax = 0;
                for (Question q : themeQs) {
                    QuestionAnswerResponse qr = buildQuestionAnswerResponse(
                            q, answersMap.get(q.getId()), decisionsMap.get(q.getId()));
                    qResponses.add(qr); themeEarned += qr.getEarnedPoints(); themeMax += qr.getPoints();
                }

                totalEarned += themeEarned; totalMax += themeMax;
                double themePct = themeMax > 0 ? Math.round((double) themeEarned / themeMax * 1000.0) / 10.0 : 0.0;

                themeAnswers.add(ThemeAnswersResponse.builder()
                    .themeId(themeId).themeName(themeName).themeCategory(themeCategory)
                    .totalScore(themeEarned).maxScore(themeMax).percentage(themePct)
                    .questions(qResponses).build());
            }
        }

        int rank = completedSorted(assessmentId).indexOf(session) + 1;
        int finalEarned = session.getEarnedPoints() != null ? session.getEarnedPoints() : totalEarned;
        int finalMax    = session.getTotalPoints()  != null ? session.getTotalPoints()  : totalMax;
        double finalPct = finalMax > 0 ? Math.round((double) finalEarned / finalMax * 1000.0) / 10.0 : 0.0;

        Candidate candidate = session.getCandidate();
        return CandidateFullResultResponse.builder()
            .submissionId(session.getId()).candidateId(candidate.getId())
            .candidateName(candidate.getFirstName() + " " + candidate.getLastName())
            .candidateEmail(candidate.getEmail()).jobTitle(assessment.getJob().getTitle())
            .testName(assessment.getName()).testId(assessmentId)
            .status(session.getStatus().name())
            .startedAt(session.getStartedAt() != null ? session.getStartedAt().toString() : null)
            .completedAt(session.getCompletedAt() != null ? session.getCompletedAt().toString() : null)
            .durationMinutes(computeDuration(session))
            .totalScore(finalEarned).maxScore(finalMax).percentage(finalPct)
            .rank(rank > 0 ? rank : null).themes(List.of()).themeAnswers(themeAnswers).build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private QuestionAnswerResponse buildQuestionAnswerResponse(Question q, Object rawAnswer,
                                                               AnswerDecisionResponse decision) {
        QuestionAnswerResponse.QuestionAnswerResponseBuilder b = QuestionAnswerResponse.builder()
            .questionId(q.getId()).title(q.getTitle() != null ? q.getTitle() : "")
            .statement(stripHtml(q.getStatement() != null ? q.getStatement() : q.getText()))
            .type(q.getKind() != null ? q.getKind().name() : "QCM")
            .questionType(q.getQuestionType() != null ? q.getQuestionType().name() : "RADIO")
            .complexity(q.getComplexity()).timeLimit(q.getTimeLimit()).memoryLimit(q.getMemoryLimit())
            .points(q.getPoints() != null ? q.getPoints() : 0)
            .orderIndex(q.getOrderIndex() != null ? q.getOrderIndex() : 0)
            .answerDecision(decision != null ? decision.getDecision() : null)
            .answerNote(decision != null ? decision.getNote() : null)
            .manualPoints(decision != null ? decision.getManualPoints() : null);

        if (q.getKind() == Question.QuestionKind.PROBLEM_SOLVING) {
            String code = null, language = null;
            List<TestCaseAnswerResponse> tcResults = new ArrayList<>();

            if (rawAnswer instanceof Map<?, ?> rawMap) {
                Map<String, Object> ans = (Map<String, Object>) rawMap;
                code     = (String) ans.get("code");
                language = (String) ans.get("language");
                Object tcrRaw = ans.get("testResults");
                if (tcrRaw instanceof List<?> tcrList) {
                    for (Object item : tcrList) {
                        if (item instanceof Map<?, ?> tcMap) {
                            Map<String, Object> tc = (Map<String, Object>) tcMap;
                            tcResults.add(TestCaseAnswerResponse.builder()
                                .input((String) tc.get("input")).expectedOutput((String) tc.get("expected"))
                                .actualOutput((String) tc.get("actual"))
                                .passed(Boolean.TRUE.equals(tc.get("passed")))
                                .points(toInt(tc.get("points"))).earnedPoints(toInt(tc.get("earnedPoints")))
                                .executionMs(toLong(tc.get("executionMs")))
                                .isVisible(Boolean.TRUE.equals(tc.get("isVisible"))).build());
                        }
                    }
                }
            }

            if (tcResults.isEmpty() && q.getTestCases() != null) {
                for (Question.TestCase tc : q.getTestCases()) {
                    tcResults.add(TestCaseAnswerResponse.builder()
                        .input(tc.getInput()).expectedOutput(tc.getOutput()).actualOutput(null)
                        .passed(false).points(tc.getPoints() != null ? tc.getPoints() : 0)
                        .earnedPoints(0).executionMs(null).isVisible(tc.isVisible()).build());
                }
            }

            int earned = tcResults.stream().mapToInt(TestCaseAnswerResponse::getEarnedPoints).sum();
            return b.submittedCode(code).submittedLanguage(language)
                    .testCases(tcResults).options(Collections.emptyList()).earnedPoints(earned).build();
        }

        if (q.getQuestionType() == Question.QuestionType.LIKERT) {
            Integer selectedLikert = null; int earned = 0;
            if (rawAnswer instanceof Number num) {
                selectedLikert = num.intValue();
                List<Integer> lp = q.getLikertPoints();
                if (lp != null && selectedLikert >= 1 && selectedLikert <= lp.size()) {
                    Integer pts = lp.get(selectedLikert - 1);
                    earned = pts != null ? pts : 0;
                }
            }
            return b.options(Collections.emptyList())
                    .likertPoints(q.getLikertPoints() != null ? q.getLikertPoints() : Collections.emptyList())
                    .selectedLikert(selectedLikert).earnedPoints(earned).build();
        }

        // QCM (RADIO / CHECKBOX)
        Set<String> selectedIds = new HashSet<>();
        if (rawAnswer instanceof String str) selectedIds.add(str);
        else if (rawAnswer instanceof List<?> list)
            for (Object o : list) { if (o instanceof String s) selectedIds.add(s); }

        List<QcmOptionAnswerResponse> optionResponses = new ArrayList<>();
        int earned = 0;
        List<Question.QcmOption> opts = q.getQcmOptions() != null ? q.getQcmOptions() : Collections.emptyList();
        for (Question.QcmOption opt : opts) {
            boolean selected = opt.getId() != null && selectedIds.contains(opt.getId());
            int optPts = opt.getPoints() != null ? opt.getPoints() : 0;
            if (selected) earned += optPts;
            optionResponses.add(QcmOptionAnswerResponse.builder()
                .id(opt.getId()).text(opt.getText()).optionText(opt.getText())
                .isCorrect(opt.isCorrect()).points(optPts).selected(selected).build());
        }

        return b.options(optionResponses).earnedPoints(earned)
                .testCases(Collections.emptyList())
                .likertPoints(q.getLikertPoints() != null ? q.getLikertPoints() : Collections.emptyList())
                .build();
    }

    private Assessment findAssessment(String id) {
        return assessmentRepository.findByIdWithThemes(id)
            .orElseThrow(() -> new RuntimeException("Assessment not found: " + id));
    }

    private List<TestSession> completedSorted(String assessmentId) {
        return testSessionRepository.findByAssessmentId(assessmentId).stream()
            .filter(s -> s.getStatus() == TestSession.SessionStatus.COMPLETED)
            .sorted(Comparator.comparingDouble(s -> -(s.getScore() != null ? s.getScore() : 0)))
            .collect(Collectors.toList());
    }

    private double safeScore(TestSession s) {
        if (s.getTotalPoints() == null || s.getTotalPoints() == 0) return 0;
        int earned = s.getEarnedPoints() != null ? s.getEarnedPoints() : 0;
        return Math.round((double) earned / s.getTotalPoints() * 1000.0) / 10.0;
    }

    private Integer computeDuration(TestSession session) {
        if (session.getStartedAt() != null && session.getCompletedAt() != null)
            return (int) java.time.Duration.between(session.getStartedAt(), session.getCompletedAt()).toMinutes();
        return null;
    }

    private Map<String, Object> parseSavedAnswers(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, AnswerDecisionResponse> parseDecisions(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        Map<String, AnswerDecisionResponse> result = new LinkedHashMap<>();
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {});
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
        } catch (Exception e) { /* return empty */ }
        return result;
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replaceAll("\\s+", " ").trim();
    }

    private int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) { try { return Integer.parseInt(s); } catch (Exception e) {} }
        return 0;
    }

    private Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) { try { return Long.parseLong(s); } catch (Exception e) {} }
        return null;
    }
}
