package com.nexgenai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexgenai.dto.jobtest.JobTestDtos.*;
import com.nexgenai.dto.technicaltest.*;
import com.nexgenai.model.*;
import com.nexgenai.model.enums.AntiCheatRiskLevel;
import com.nexgenai.model.enums.AssessmentType;
import com.nexgenai.model.enums.StageType;
import com.nexgenai.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Unified Assessment Service — Phase 2 refactoring.
 *
 * Merges the previous {@code JobTestService} (assessment CRUD, themes,
 * psychometric models, candidate results) and {@code TechnicalTestService}
 * (technical session lifecycle, code execution, anti-cheat) into a single
 * service that works with the Phase 1 unified models:
 * {@link Assessment}, {@link TestSession}, {@link Question}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssessmentService {

    // ── Repositories ──────────────────────────────────────────────────────────

    private final AssessmentRepository        assessmentRepository;
    private final TestThemeRepository         themeRepository;
    private final ThemeModelRepository        themeModelRepository;
    private final PsychometricModelRepository modelRepository;
    private final QuestionRepository          questionRepository;
    private final QuestionOptionRepository    optionRepository;
    private final JobRepository               jobRepository;
    private final TestSubmissionRepository    submissionRepository;
    private final CandidateAnswerRepository   answerRepository;
    private final TestSessionRepository       testSessionRepository;
    private final CandidateRepository         candidateRepository;
    private final AntiCheatEventRepository    antiCheatRepository;
    private final WorkflowStageRepository     workflowStageRepository;

    // ── Services ──────────────────────────────────────────────────────────────

    private final CodeExecutionService codeExecutionService;

    // ── Config ────────────────────────────────────────────────────────────────

    private final ObjectMapper objectMapper;

    @Value("${app.question.image-dir:uploads/questions}")
    private String imageDir;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    // ══════════════════════════════════════════════════════════════════════════
    // JOBS WITH TESTS (ASSESSMENTS)
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<JobWithTestsResponse> getJobsWithTests(String role, String evaluatorId) {
        List<Job> jobs = jobRepository.findAll();
        List<JobWithTestsResponse> result = new ArrayList<>();

        for (Job job : jobs) {
            List<TestSummaryResponse> allTests = new ArrayList<>();
            List<Assessment> assessments = assessmentRepository.findByJobId(job.getId());

            // ── 1. Assessments already configured ─────────────────────────────
            for (Assessment a : assessments) {
                WorkflowStage linkedStage = null;
                if (a.getWorkflowStageId() != null && job.getWorkflowStages() != null) {
                    linkedStage = job.getWorkflowStages().stream()
                        .filter(s -> s.getId().equals(a.getWorkflowStageId()))
                        .findFirst().orElse(null);
                }

                if ("HR".equals(role)) {
                    if (linkedStage == null) continue;
                    if (linkedStage.getStageType() != StageType.RH_TEST) continue;
                }

                String effectiveAssigneeId = resolveAssigneeId(linkedStage, job);
                if ("HR".equals(role) && linkedStage != null
                        && linkedStage.getStageType() != StageType.RH_TEST) continue;

                if (evaluatorId != null
                        && effectiveAssigneeId != null
                        && !evaluatorId.equals(effectiveAssigneeId)) continue;

                if (evaluatorId != null
                        && effectiveAssigneeId == null
                        && a.getWorkflowStageId() != null) continue;

                String stageTypeName = linkedStage != null && linkedStage.getStageType() != null
                        ? linkedStage.getStageType().name() : null;

                int candidatesCount = countCandidatesForAssessment(a.getId(), stageTypeName);

                allTests.add(TestSummaryResponse.builder()
                    .id(a.getId())
                    .name(a.getName())
                    .description(a.getDescription())
                    .status(a.getStatus().name())
                    .themesCount(a.getThemes() != null ? a.getThemes().size() : 0)
                    .questionsCount(countAllQuestions(a))
                    .candidatesCount(candidatesCount)
                    .completionRate(0)
                    .createdAt(a.getCreatedAt() != null ? a.getCreatedAt().toString() : null)
                    .stageType(stageTypeName)
                    .source("ASSESSMENT")
                    .build());
            }

            // ── 2. WorkflowStages not yet configured ──────────────────────────
            if (job.getWorkflowStages() != null) {
                for (WorkflowStage stage : job.getWorkflowStages()) {
                    if (stage.getStageType() == null) continue;

                    String stageType = stage.getStageType().name();

                    if ("HR".equals(role) && stage.getStageType() != StageType.RH_TEST) continue;
                    if (!"HR".equals(role) && !stageType.contains("TEST")) continue;

                    String effectiveAssigneeId = resolveAssigneeId(stage, job);

                    if (evaluatorId != null
                            && effectiveAssigneeId != null
                            && !evaluatorId.equals(effectiveAssigneeId)) continue;

                    if (evaluatorId != null && effectiveAssigneeId == null) continue;

                    boolean alreadyLinked = assessments.stream()
                        .anyMatch(a -> stage.getId().equals(a.getWorkflowStageId()));

                    if (!alreadyLinked) {
                        allTests.add(TestSummaryResponse.builder()
                            .id(stage.getId())
                            .name(stage.getName())
                            .description(stage.getDescription())
                            .status("DRAFT")
                            .themesCount(0)
                            .questionsCount(0)
                            .candidatesCount(0)
                            .completionRate(0)
                            .createdAt(job.getCreatedAt() != null ? job.getCreatedAt().toString() : null)
                            .stageType(stageType)
                            .assignedTo(stage.getAssignedTo())
                            .assigneeId(effectiveAssigneeId)
                            .source("WORKFLOW_STAGE")
                            .build());
                    }
                }
            }

            if (!allTests.isEmpty()) {
                result.add(JobWithTestsResponse.builder()
                    .id(job.getId())
                    .title(job.getTitle())
                    .department(job.getDepartment())
                    .location(job.getLocation())
                    .status(job.getStatus().name())
                    .tests(allTests)
                    .build());
            }
        }
        return result;
    }

    private int countCandidatesForAssessment(String assessmentId, String stageTypeName) {
        return testSessionRepository.countByAssessmentId(assessmentId);
    }

    private String resolveAssigneeId(WorkflowStage stage, Job job) {
        if (stage == null) return null;

        if (stage.getAssigneeId() != null && !stage.getAssigneeId().isBlank()) {
            return stage.getAssigneeId();
        }

        if (stage.getAssessmentId() != null && job.getAssessments() != null) {
            return job.getAssessments().stream()
                .filter(a -> stage.getAssessmentId().equals(a.getId())
                          || stage.getAssessmentId().equals(a.getLinkId()))
                .map(Assessment::getAssigneeId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse(null);
        }

        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CONFIGURE FROM STAGE
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public JobTestResponse configureFromStage(String workflowStageId, String jobId) {
        assessmentRepository.findByWorkflowStageId(workflowStageId).ifPresent(existing -> {
            throw new RuntimeException("Assessment already configured for this stage");
        });

        Job job = findJob(jobId);
        WorkflowStage stage = job.getWorkflowStages().stream()
            .filter(s -> s.getId().equals(workflowStageId))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("Stage not found"));

        // Determine assessment type from stage type
        AssessmentType type = stage.getStageType() == StageType.RH_TEST
            ? AssessmentType.RH : AssessmentType.TECHNICAL;

        Assessment assessment = Assessment.builder()
            .job(job)
            .name(stage.getName())
            .description(stage.getDescription())
            .type(type)
            .status(Assessment.AssessmentStatus.DRAFT)
            .workflowStageId(workflowStageId)
            .build();

        return mapAssessmentFull(assessmentRepository.save(assessment));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ASSESSMENTS — CRUD
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<JobTestResponse> getAllTests() {
        return assessmentRepository.findAllWithJob().stream()
            .map(this::mapAssessmentSummary).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public JobTestResponse getTest(String id) {
        return mapAssessmentFull(findAssessment(id));
    }

    @Transactional
    public JobTestResponse createTest(CreateTestRequest req) {
        Job job = findJob(req.getJobId());
        Assessment assessment = Assessment.builder()
            .job(job).name(req.getName()).description(req.getDescription())
            .type(AssessmentType.TECHNICAL) // default; can be overridden
            .status(Assessment.AssessmentStatus.DRAFT).build();
        return mapAssessmentSummary(assessmentRepository.save(assessment));
    }

    @Transactional
    public JobTestResponse updateTest(String id, UpdateTestRequest req) {
        Assessment assessment = findAssessment(id);
        if (req.getName()        != null) assessment.setName(req.getName());
        if (req.getDescription() != null) assessment.setDescription(req.getDescription());
        return mapAssessmentSummary(assessmentRepository.save(assessment));
    }

    @Transactional
    public void activateTest(String id) {
        Assessment assessment = findAssessment(id);
        assessment.setStatus(Assessment.AssessmentStatus.ACTIVE);
        assessmentRepository.save(assessment);
    }

    @Transactional
    public void archiveTest(String id) {
        Assessment assessment = findAssessment(id);
        assessment.setStatus(Assessment.AssessmentStatus.ARCHIVED);
        assessmentRepository.save(assessment);
    }

    @Transactional
    public void deleteTest(String id) {
        assessmentRepository.deleteById(id);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SAVE FULL TEST
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public JobTestResponse saveFullTest(String assessmentId, SaveFullTestRequest req) {
        Assessment assessment = findAssessment(assessmentId);

        if (req.getName()        != null) assessment.setName(req.getName());
        if (req.getDescription() != null) assessment.setDescription(req.getDescription());
        if (req.getStatus()      != null) {
            try { assessment.setStatus(Assessment.AssessmentStatus.valueOf(req.getStatus())); }
            catch (IllegalArgumentException ignored) {}
        }

        if (req.getThemes() == null || req.getThemes().isEmpty()) {
            return mapAssessmentFull(assessmentRepository.save(assessment));
        }

        Set<String> existingThemeIds = assessment.getThemes().stream()
            .map(TestTheme::getId).collect(Collectors.toSet());

        Set<String> incomingThemeIds = req.getThemes().stream()
            .map(SaveFullTestRequest.ThemePayload::getId)
            .filter(id -> id != null && existingThemeIds.contains(id))
            .collect(Collectors.toSet());

        List<String> toDeleteIds = existingThemeIds.stream()
            .filter(id -> !incomingThemeIds.contains(id))
            .collect(Collectors.toList());

        for (String orphanId : toDeleteIds) {
            questionRepository.deleteByThemeId(orphanId);
            themeRepository.deleteById(orphanId);
        }

        assessment = findAssessment(assessmentId);

        for (SaveFullTestRequest.ThemePayload thPayload : req.getThemes()) {
            TestTheme theme = resolveOrCreateTheme(thPayload, assessment);
            if (theme == null) continue;
            questionRepository.deleteByThemeId(theme.getId());
            if (thPayload.getQuestions() != null) {
                int order = 0;
                for (SaveFullTestRequest.QuestionPayload qp : thPayload.getQuestions()) {
                    qp.setOrderIndex(order++);
                    questionRepository.save(buildQuestion(qp, theme, thPayload.getType()));
                }
            }
        }
        return mapAssessmentFull(assessmentRepository.save(assessment));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // THEMES
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public JobTestResponse addTheme(String assessmentId, CreateThemeRequest req) {
        Assessment assessment = findAssessment(assessmentId);
        TestTheme theme = TestTheme.builder()
            .name(req.getName())
            .category(TestTheme.ThemeCategory.valueOf(req.getCategory().toUpperCase()))
            .orderIndex(assessment.getThemes().size()).build();
        assessment.addTheme(theme);
        assessmentRepository.save(assessment);
        return mapAssessmentFull(findAssessment(assessmentId));
    }

    @Transactional
    public void deleteTheme(String assessmentId, String themeId) {
        TestTheme theme = themeRepository.findById(themeId)
            .orElseThrow(() -> new RuntimeException("Theme not found"));
        if (!theme.getAssessment().getId().equals(assessmentId))
            throw new RuntimeException("Theme does not belong to this assessment");
        questionRepository.deleteByThemeId(themeId);
        themeRepository.delete(theme);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // THEME MODELS
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public JobTestResponse addModelToTheme(String assessmentId, String themeId, AddModelRequest req) {
        TestTheme theme = themeRepository.findById(themeId)
            .orElseThrow(() -> new RuntimeException("Theme not found"));
        if (!theme.getAssessment().getId().equals(assessmentId))
            throw new RuntimeException("Theme mismatch");
        if (themeModelRepository.existsByThemeIdAndModelId(themeId, req.getModelId()))
            throw new RuntimeException("Model already added");
        PsychometricModel model = modelRepository.findById(req.getModelId())
            .orElseThrow(() -> new RuntimeException("Model not found"));
        ThemeModel tm = ThemeModel.builder()
            .model(model).weight(req.getWeight() != null ? req.getWeight() : 50)
            .orderIndex(theme.getThemeModels().size()).build();
        theme.addThemeModel(tm);
        themeRepository.save(theme);
        return mapAssessmentFull(findAssessment(assessmentId));
    }

    @Transactional
    public void updateThemeModelWeight(String assessmentId, String themeId,
                                       String themeModelId, UpdateWeightRequest req) {
        ThemeModel tm = themeModelRepository.findById(themeModelId)
            .orElseThrow(() -> new RuntimeException("ThemeModel not found"));
        tm.setWeight(req.getWeight());
        themeModelRepository.save(tm);
    }

    @Transactional
    public void removeModelFromTheme(String assessmentId, String themeId, String themeModelId) {
        themeModelRepository.deleteById(themeModelId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PSYCHOMETRIC QUESTIONS (RH — entity-backed options)
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public QuestionResponse addPsychometricQuestion(String themeModelId, CreateQuestionRequest req) {
        ThemeModel tm = themeModelRepository.findById(themeModelId)
            .orElseThrow(() -> new RuntimeException("ThemeModel not found"));
        Question q = Question.builder()
            .text(req.getText())
            .kind(Question.QuestionKind.QCM)
            .questionType(Question.QuestionType.valueOf(req.getType().toUpperCase()))
            .orderIndex(tm.getQuestions().size()).build();
        if (req.getOptions() != null) {
            int oi = 0;
            for (CreateQuestionRequest.OptionRequest or : req.getOptions()) {
                QuestionOption opt = QuestionOption.builder()
                    .text(or.getText()).dimensionId(or.getDimensionId())
                    .points(or.getPoints() != null ? or.getPoints() : 10)
                    .orderIndex(oi++).build();
                q.addOption(opt);
            }
        }
        tm.addQuestion(q);
        themeModelRepository.save(tm);
        return mapPsychometricQuestion(q);
    }

    @Transactional
    public QuestionResponse updatePsychometricQuestion(String questionId, CreateQuestionRequest req) {
        Question q = questionRepository.findById(questionId)
            .orElseThrow(() -> new RuntimeException("Question not found"));
        q.setText(req.getText());
        q.setQuestionType(Question.QuestionType.valueOf(req.getType().toUpperCase()));
        q.getOptions().clear();
        if (req.getOptions() != null) {
            int oi = 0;
            for (CreateQuestionRequest.OptionRequest or : req.getOptions()) {
                QuestionOption opt = QuestionOption.builder()
                    .text(or.getText()).dimensionId(or.getDimensionId())
                    .points(or.getPoints() != null ? or.getPoints() : 10)
                    .orderIndex(oi++).build();
                q.addOption(opt);
            }
        }
        return mapPsychometricQuestion(questionRepository.save(q));
    }

    @Transactional
    public QuestionResponse uploadQuestionImage(String questionId, MultipartFile file) throws IOException {
        Question q = questionRepository.findById(questionId)
            .orElseThrow(() -> new RuntimeException("Question not found"));
        if (q.getImagePath() != null)
            try { Files.deleteIfExists(Paths.get(q.getImagePath())); } catch (IOException ignored) {}
        String ext      = getExtension(file.getOriginalFilename());
        String filename = questionId + "_" + System.currentTimeMillis() + ext;
        Path dir = Paths.get(imageDir); Files.createDirectories(dir);
        Path dest = dir.resolve(filename);
        Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        q.setImagePath(dest.toString());
        return mapPsychometricQuestion(questionRepository.save(q));
    }

    @Transactional
    public void deleteQuestionImage(String questionId) throws IOException {
        Question q = questionRepository.findById(questionId)
            .orElseThrow(() -> new RuntimeException("Question not found"));
        if (q.getImagePath() != null) {
            Files.deleteIfExists(Paths.get(q.getImagePath()));
            q.setImagePath(null);
            questionRepository.save(q);
        }
    }

    @Transactional
    public void deleteQuestion(String questionId) {
        Question q = questionRepository.findById(questionId)
            .orElseThrow(() -> new RuntimeException("Question not found"));
        if (q.getImagePath() != null)
            try { Files.deleteIfExists(Paths.get(q.getImagePath())); } catch (IOException ignored) {}
        questionRepository.delete(q);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SIMPLE / TECHNICAL QUESTIONS (theme-level — JSON-backed options)
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public SimpleQuestionResponse addSimpleQuestion(String themeId,
                                                    SaveFullTestRequest.QuestionPayload req,
                                                    String type) {
        TestTheme theme = themeRepository.findById(themeId)
            .orElseThrow(() -> new RuntimeException("Theme not found"));
        return mapSimpleQuestion(questionRepository.save(buildQuestion(req, theme, type)));
    }

    @Transactional
    public SimpleQuestionResponse updateSimpleQuestion(String questionId,
                                                       SaveFullTestRequest.QuestionPayload req) {
        Question q = questionRepository.findById(questionId)
            .orElseThrow(() -> new RuntimeException("Question not found"));
        q.setTitle(req.getTitle());
        q.setStatement(req.getStatement());
        q.setPoints(req.getPoints());
        q.setOrderIndex(req.getOrderIndex());
        if (req.getComplexity()   != null) q.setComplexity(req.getComplexity());
        if (req.getTimeLimit()    != null) q.setTimeLimit(req.getTimeLimit());
        if (req.getMemoryLimit()  != null) q.setMemoryLimit(req.getMemoryLimit());
        if (req.getTestCases()    != null) q.setTestCases(mapTestCases(req.getTestCases()));
        if (req.getQuestionType() != null) q.setQuestionType(
            Question.QuestionType.valueOf(req.getQuestionType().toUpperCase()));
        if (req.getOptions()      != null) q.setQcmOptions(mapQcmOptions(req.getOptions()));
        if (req.getLikertPoints() != null) q.setLikertPoints(req.getLikertPoints());
        return mapSimpleQuestion(questionRepository.save(q));
    }

    @Transactional
    public void deleteSimpleQuestion(String questionId) {
        questionRepository.deleteById(questionId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PSYCHOMETRIC MODELS
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<ModelResponse> getAllModels() {
        return modelRepository.findAllByOrderByBuiltInDescNameAsc().stream()
            .map(this::mapModel).collect(Collectors.toList());
    }

    @Transactional
    public ModelResponse createModel(CreateModelRequest req) {
        if (modelRepository.existsByName(req.getName()))
            throw new RuntimeException("A model with this name already exists");
        PsychometricModel model = PsychometricModel.builder()
            .name(req.getName()).description(req.getDescription())
            .scoringType(PsychometricModel.ScoringType.valueOf(req.getScoringType().toUpperCase()))
            .builtIn(false).build();
        if (req.getDimensions() != null) {
            int i = 0;
            for (CreateModelRequest.DimensionRequest dr : req.getDimensions()) {
                model.addDimension(ModelDimension.builder()
                    .name(dr.getName()).code(dr.getCode()).color(dr.getColor())
                    .orderIndex(i++).build());
            }
        }
        return mapModel(modelRepository.save(model));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DATA INIT — Built-in models
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public void initBuiltInModels() {
        if (modelRepository.count() > 0) return;
        modelRepository.save(buildModel("DISC Behavioral Profile",
            "Dominance, Influence, Steadiness, Conscientiousness",
            PsychometricModel.ScoringType.SUM,
            List.of(dim("Dominance","D","#ef4444",0), dim("Influence","I","#f59e0b",1),
                    dim("Steadiness","S","#10b981",2), dim("Conscientiousness","C","#6366f1",3))));
        modelRepository.save(buildModel("Big Five (OCEAN)",
            "Openness, Conscientiousness, Extraversion, Agreeableness, Neuroticism",
            PsychometricModel.ScoringType.AVERAGE,
            List.of(dim("Openness","O","#8b5cf6",0), dim("Conscientiousness","C","#3b82f6",1),
                    dim("Extraversion","E","#f59e0b",2), dim("Agreeableness","A","#10b981",3),
                    dim("Neuroticism","N","#ef4444",4))));
        modelRepository.save(buildModel("Emotional Intelligence (EQ-i)",
            "Self-awareness, regulation, motivation, empathy, social skills",
            PsychometricModel.ScoringType.WEIGHTED,
            List.of(dim("Self-Awareness","SA","#06b6d4",0), dim("Self-Regulation","SR","#8b5cf6",1),
                    dim("Motivation","M","#f59e0b",2), dim("Empathy","EM","#ec4899",3),
                    dim("Social Skills","SS","#10b981",4))));
        log.info("✅ Built-in psychometric models initialized");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TECHNICAL SESSION — Start / Save / Submit / Run Code
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

        if (session.getStatus() == TestSession.SessionStatus.COMPLETED) {
            throw new RuntimeException("Test already submitted");
        }

        Map<String, Object> savedAnswers = parseSavedAnswers(session.getAnswersJson());

        List<SimpleQuestionDto> questions = assessment.getThemes().stream()
                .flatMap(theme -> {
                    List<Question> qs = questionRepository.findByThemeIdOrderByOrderIndex(theme.getId());
                    return qs.stream();
                })
                .map(q -> mapToTechnicalDto(q, savedAnswers.get(q.getId())))
                .collect(Collectors.toList());

        int timeLimitSeconds = computeTimeLimit(questions);
        session.setTimeLimitSeconds(timeLimitSeconds);
        testSessionRepository.save(session);

        return TechnicalSessionDto.builder()
                .sessionId(session.getId())
                .testId(assessmentId)
                .testName(assessment.getName())
                .jobTitle(assessment.getJob().getTitle())
                .timeLimitSeconds(timeLimitSeconds)
                .questions(questions)
                .build();
    }

    private TestSession createTechnicalSession(Candidate candidate, Assessment assessment) {
        TestSession s = TestSession.builder()
                .candidate(candidate)
                .assessment(assessment)
                .type(AssessmentType.TECHNICAL)
                .status(TestSession.SessionStatus.IN_PROGRESS)
                .startedAt(LocalDateTime.now())
                .answersJson("{}")
                .antiCheatJson("[]")
                .build();
        return testSessionRepository.save(s);
    }

    private int computeTimeLimit(List<SimpleQuestionDto> questions) {
        int total = questions.stream().mapToInt(q -> {
            if ("PROBLEM_SOLVING".equals(q.getType())) {
                return q.getTimeLimit() != null ? (int)(q.getTimeLimit() * 60) : 600;
            } else {
                return 120;
            }
        }).sum();
        return Math.max(900, Math.min(total, 10800));
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

        if (session.getStatus() == TestSession.SessionStatus.COMPLETED) {
            throw new RuntimeException("Already submitted");
        }

        if (req.getAnswers() != null && !req.getAnswers().isEmpty()) {
            try {
                session.setAnswersJson(objectMapper.writeValueAsString(req.getAnswers()));
            } catch (Exception ignored) {}
        }

        if (req.getAntiCheatLog() != null) {
            try {
                session.setAntiCheatJson(objectMapper.writeValueAsString(req.getAntiCheatLog()));
            } catch (Exception ignored) {}
        }

        Map<String, Object> answers = parseSavedAnswers(session.getAnswersJson());
        Assessment assessment = session.getAssessment();

        List<QuestionResultDto> questionResults = new ArrayList<>();
        int totalPoints  = 0;
        int earnedPoints = 0;

        for (TestTheme theme : assessment.getThemes()) {
            List<Question> questions = questionRepository.findByThemeIdOrderByOrderIndex(theme.getId());

            for (Question q : questions) {
                Object rawAnswer = answers.get(q.getId());
                int maxPts = q.getPoints() != null ? q.getPoints() : 10;
                totalPoints += maxPts;

                int pts = scoreQuestion(q, rawAnswer, answers, req);
                earnedPoints += pts;

                questionResults.add(QuestionResultDto.builder()
                        .questionId(q.getId())
                        .title(q.getTitle())
                        .type(q.getKind() != null ? q.getKind().name() : "QCM")
                        .earnedPoints(pts)
                        .maxPoints(maxPts)
                        .build());
            }
        }

        // Re-save enriched answers (with testResults from code execution)
        try {
            session.setAnswersJson(objectMapper.writeValueAsString(answers));
        } catch (Exception e) {
            log.warn("Could not re-save enriched answers for session {}", sessionId);
        }

        double score = totalPoints > 0
                ? Math.round((double) earnedPoints / totalPoints * 1000.0) / 10.0
                : 0.0;

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
                .score(score)
                .totalPoints(totalPoints)
                .earnedPoints(earnedPoints)
                .questionsResults(questionResults)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RH SESSION — Start / Save / Submit (psychometric tests)
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

        // If already completed, return with 0 time
        if (session.getStatus() == TestSession.SessionStatus.COMPLETED) {
            List<com.nexgenai.dto.test.QuestionDto> questions = buildRhQuestions(assessment);
            return com.nexgenai.dto.test.TestSessionDto.builder()
                    .sessionId(session.getId())
                    .testId(assessmentId)
                    .testName(assessment.getName())
                    .totalQuestions(questions.size())
                    .timeLimit(0)
                    .timeLeftSeconds(0)
                    .questions(questions)
                    .build();
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
                .sessionId(session.getId())
                .testId(assessmentId)
                .testName(assessment.getName())
                .totalQuestions(questions.size())
                .timeLimit(timeLimitMinutes)
                .timeLeftSeconds(timeLeftSeconds)
                .questions(questions)
                .build();
    }

    private List<com.nexgenai.dto.test.QuestionDto> buildRhQuestions(Assessment assessment) {
        return assessment.getThemes().stream()
                .flatMap(th -> th.getThemeModels().stream())
                .flatMap(tm -> tm.getQuestions().stream())
                .map(this::mapRhQuestion)
                .collect(Collectors.toList());
    }

    @Transactional
    public int submitRhTest(String sessionId, String email) {
        TestSession session = testSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        if (session.getStatus() == TestSession.SessionStatus.COMPLETED)
            return session.getScore() != null ? session.getScore() : 0;

        Map<String, List<String>> answers = new HashMap<>();
        try {
            if (session.getAnswersJson() != null) {
                answers = objectMapper.readValue(session.getAnswersJson(),
                        new TypeReference<Map<String, List<String>>>() {});
            }
        } catch (Exception ignored) {}

        Assessment assessment = assessmentRepository.findByIdWithThemes(session.getAssessment().getId())
                .orElseThrow(() -> new RuntimeException("Assessment not found"));

        int totalPoints  = 0;
        int earnedPoints = 0;

        for (TestTheme theme : assessment.getThemes()) {
            for (ThemeModel tm : theme.getThemeModels()) {
                int weight = (tm.getWeight() != null && tm.getWeight() > 0) ? tm.getWeight() : 1;

                for (Question question : tm.getQuestions()) {
                    List<QuestionOption> opts = new ArrayList<>(question.getOptions());
                    int maxPts = opts.stream()
                            .mapToInt(o -> o.getPoints() != null ? o.getPoints() : 0)
                            .max().orElse(0);
                    totalPoints += maxPts * weight;

                    List<String> chosen = answers.getOrDefault(question.getId(), List.of());
                    int pts = opts.stream()
                            .filter(o -> chosen.contains(o.getId()))
                            .mapToInt(o -> o.getPoints() != null ? o.getPoints() : 0)
                            .sum();
                    earnedPoints += Math.max(0, pts) * weight;
                }
            }
        }

        int score = totalPoints > 0 ? Math.round((float) earnedPoints / totalPoints * 100) : 0;

        session.setScore(score);
        session.setStatus(TestSession.SessionStatus.COMPLETED);
        session.setCompletedAt(LocalDateTime.now());
        testSessionRepository.save(session);

        log.info("RH test {} submitted by {} — Score: {}/100 (earned={}, total={})",
                sessionId, email, score, earnedPoints, totalPoints);

        return score;
    }

    private int resolveRhTimeLimit(Assessment assessment) {
        try {
            if (assessment.getDuration() != null && assessment.getDuration() > 0) {
                return assessment.getDuration();
            }
            // Fallback: check linked workflow stage
            if (assessment.getWorkflowStageId() != null) {
                Optional<WorkflowStage> stageOpt = workflowStageRepository.findById(assessment.getWorkflowStageId());
                if (stageOpt.isPresent()) {
                    WorkflowStage stage = stageOpt.get();
                    if (stage.getAssessmentId() != null) {
                        Optional<Assessment> linkedOpt = assessmentRepository.findByLinkId(stage.getAssessmentId());
                        if (linkedOpt.isPresent() && linkedOpt.get().getDuration() != null
                                && linkedOpt.get().getDuration() > 0) {
                            return linkedOpt.get().getDuration();
                        }
                    }
                }
            }
            return 45; // default
        } catch (Exception e) {
            log.error("Error resolving time limit for assessment {}", assessment.getId(), e);
            return 45;
        }
    }

    private TestSession createRhSession(Candidate candidate, Assessment assessment) {
        TestSession s = TestSession.builder()
                .candidate(candidate)
                .assessment(assessment)
                .type(AssessmentType.RH)
                .status(TestSession.SessionStatus.IN_PROGRESS)
                .startedAt(LocalDateTime.now())
                .answersJson("{}")
                .build();
        return testSessionRepository.save(s);
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
            if (session.getAnswersJson() != null && !session.getAnswersJson().isBlank()) {
                answers = objectMapper.readValue(session.getAnswersJson(),
                        new TypeReference<Map<String, List<String>>>() {});
            }
        } catch (Exception ignored) {}

        answers.put(req.getQuestionId(), req.getOptionIds() != null ? req.getOptionIds() : List.of());

        try {
            session.setAnswersJson(objectMapper.writeValueAsString(answers));
        } catch (Exception e) {
            throw new RuntimeException("Could not serialize answers", e);
        }
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
        entry.put("type",          event.getType());
        entry.put("detail",        event.getDetail());
        entry.put("questionIndex", event.getQuestionIndex());
        entry.put("timestamp",     event.getTimestamp());
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
            .filter(e -> "DEV_TOOLS".equals(e.getType())
                      || "DEVTOOLS_ATTEMPT".equals(e.getType())
                      || "DEVTOOLS".equals(e.getType())).count();

        int totalPts  = session.getTotalPoints()  != null ? session.getTotalPoints()  : 0;
        int earnedPts = session.getEarnedPoints() != null ? session.getEarnedPoints() : 0;

        List<AntiCheatReportDto.QuestionResult> questionsResults = buildAntiCheatQuestionResults(session);

        return AntiCheatReportDto.builder()
                .sessionId(sessionId)
                .candidateName(session.getCandidate().getFirstName()
                    + " " + session.getCandidate().getLastName())
                .testName(session.getAssessment().getName())
                .score(session.getScore() != null ? session.getScore() : 0)
                .totalPoints(totalPts)
                .earnedPoints(earnedPts)
                .tabSwitchCount((int) tabSwitches)
                .pasteCount((int) pastes)
                .blurCount((int) blurs)
                .devToolsAttempts((int) devTools)
                .totalEvents(events.size())
                .riskLevel(session.getRiskLevel() != null ? session.getRiskLevel() : "LOW")
                .events(events)
                .questionsResults(questionsResults)
                .build();
    }

    private List<AntiCheatReportDto.QuestionResult> buildAntiCheatQuestionResults(TestSession session) {
        Assessment assessment = session.getAssessment();
        Map<String, Object> answers = parseSavedAnswers(session.getAnswersJson());
        List<AntiCheatReportDto.QuestionResult> results = new ArrayList<>();

        for (TestTheme theme : assessment.getThemes()) {
            List<Question> questions = questionRepository.findByThemeIdOrderByOrderIndex(theme.getId());

            if (questions.isEmpty() && !answers.isEmpty()) {
                questions = questionRepository.findByIdIn(new ArrayList<>(answers.keySet()));
            }

            for (Question q : questions) {
                Object rawAnswer = answers.get(q.getId());
                int maxPts = q.getPoints() != null ? q.getPoints() : 0;
                int earned = estimateEarnedFromAnswer(q, rawAnswer);
                results.add(AntiCheatReportDto.QuestionResult.builder()
                    .questionId(q.getId())
                    .title(q.getTitle() != null ? q.getTitle() : "Question")
                    .type(q.getKind() != null ? q.getKind().name() : "QCM")
                    .earnedPoints(earned)
                    .maxPoints(maxPts)
                    .build());
            }
        }
        return results;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CANDIDATE RESULTS — RH (psychometric) overview
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
            if (val instanceof List<?> list && !list.isEmpty()) {
                answerMap.put(entry.getKey(), list.get(0).toString());
            } else if (val instanceof String s) {
                answerMap.put(entry.getKey(), s);
            }
        }

        List<ThemeResultResponse>  themeResults     = new ArrayList<>();
        List<ModelAnswersResponse> modelAnswersList  = new ArrayList<>();

        for (TestTheme theme : assessment.getThemes()) {
            List<ThemeModelResultResponse> modelResults = new ArrayList<>();
            int themeTotal = 0, themeMax = 0;

            for (ThemeModel tm : theme.getThemeModels()) {
                Map<String, ModelDimension> dimById = tm.getModel().getDimensions().stream()
                    .collect(Collectors.toMap(ModelDimension::getId, d -> d));

                Map<String, Integer> dimScores = new HashMap<>();
                Map<String, Integer> dimMaxMap = new HashMap<>();
                for (ModelDimension d : tm.getModel().getDimensions()) {
                    dimScores.put(d.getId(), 0);
                    dimMaxMap.put(d.getId(), 0);
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
                            if (opt.getDimensionId() != null)
                                dimScores.merge(opt.getDimensionId(), pts, Integer::sum);
                            modelEarned += pts;
                            qEarned     += pts;
                            answered++;
                        }

                        ModelDimension dimObj = opt.getDimensionId() != null
                            ? dimById.get(opt.getDimensionId()) : null;

                        optionResponses.add(QcmOptionAnswerResponse.builder()
                            .id(opt.getId())
                            .text(opt.getText())
                            .isCorrect(false)
                            .points(pts)
                            .selected(selected)
                            .dimensionId(opt.getDimensionId())
                            .dimensionName(dimObj != null ? dimObj.getName() : null)
                            .optionText(opt.getText())
                            .build());
                    }

                    int maxPts = q.getOptions().stream()
                        .mapToInt(o -> o.getPoints() != null ? o.getPoints() : 0).sum();

                    String imageUrl = q.getImagePath() != null
                        ? baseUrl + "/api/v1/job-tests/questions/" + q.getId() + "/image" : null;

                    questionDetails.add(QuestionAnswerResponse.builder()
                        .questionId(q.getId())
                        .title("")
                        .statement(q.getText())
                        .type("QCM")
                        .questionType("RADIO")
                        .points(maxPts)
                        .earnedPoints(qEarned)
                        .orderIndex(q.getOrderIndex() != null ? q.getOrderIndex() : 0)
                        .imageUrl(imageUrl)
                        .options(optionResponses)
                        .likertPoints(List.of())
                        .testCases(List.of())
                        .build());
                }

                themeTotal += modelEarned;
                themeMax   += modelMaxPts;

                modelAnswersList.add(ModelAnswersResponse.builder()
                    .themeModelId(tm.getId())
                    .modelName(tm.getModel().getName())
                    .questions(questionDetails)
                    .build());

                List<DimensionScoreResponse> dimResponses = tm.getModel().getDimensions().stream()
                    .sorted(Comparator.comparingInt(d -> d.getOrderIndex() != null ? d.getOrderIndex() : 0))
                    .map(d -> {
                        int ds  = dimScores.getOrDefault(d.getId(), 0);
                        int dm  = dimMaxMap.getOrDefault(d.getId(), 0);
                        double pct = dm > 0
                            ? Math.round((double) ds / dm * 1000.0) / 10.0 : 0.0;
                        return DimensionScoreResponse.builder()
                            .dimensionId(d.getId())
                            .dimensionName(d.getName())
                            .dimensionCode(d.getCode())
                            .color(d.getColor())
                            .score(ds).maxScore(dm).percentage(pct)
                            .build();
                    }).collect(Collectors.toList());

                double modelPct = modelMaxPts > 0
                    ? Math.round((double) modelEarned / modelMaxPts * 1000.0) / 10.0 : 0.0;

                modelResults.add(ThemeModelResultResponse.builder()
                    .themeModelId(tm.getId())
                    .modelId(tm.getModel().getId())
                    .modelName(tm.getModel().getName())
                    .scoringType(tm.getModel().getScoringType().name())
                    .weight(tm.getWeight() != null ? tm.getWeight() : 50)
                    .totalScore(modelEarned).maxScore(modelMaxPts).percentage(modelPct)
                    .questionsCount(tm.getQuestions().size())
                    .answeredCount(answered)
                    .dimensions(dimResponses)
                    .build());
            }

            double themePct = themeMax > 0
                ? Math.round((double) themeTotal / themeMax * 1000.0) / 10.0 : 0.0;

            themeResults.add(ThemeResultResponse.builder()
                .themeId(theme.getId())
                .themeName(theme.getName())
                .themeCategory(theme.getCategory() != null ? theme.getCategory().name() : "CUSTOM")
                .totalScore(themeTotal).maxScore(themeMax).percentage(themePct)
                .models(modelResults)
                .build());
        }

        List<TestSession> allCompleted = testSessionRepository.findByAssessmentId(assessmentId).stream()
            .filter(s -> s.getStatus() == TestSession.SessionStatus.COMPLETED)
            .sorted(Comparator.comparingDouble(s -> -(s.getScore() != null ? s.getScore() : 0)))
            .collect(Collectors.toList());
        int rank = allCompleted.indexOf(session) + 1;

        Integer durationMinutes = null;
        if (session.getStartedAt() != null && session.getCompletedAt() != null) {
            durationMinutes = (int) java.time.Duration
                .between(session.getStartedAt(), session.getCompletedAt()).toMinutes();
        }

        int score = session.getScore() != null ? session.getScore() : 0;

        return CandidateTestResultResponse.builder()
            .submissionId(session.getId())
            .candidateId(candidate.getId())
            .candidateName(candidate.getFirstName() + " " + candidate.getLastName())
            .candidateEmail(candidate.getEmail())
            .jobTitle(assessment.getJob().getTitle())
            .testName(assessment.getName()).testId(assessmentId)
            .status(session.getStatus().name())
            .startedAt(session.getStartedAt() != null ? session.getStartedAt().toString() : null)
            .completedAt(session.getCompletedAt() != null ? session.getCompletedAt().toString() : null)
            .durationMinutes(durationMinutes)
            .totalScore(score).maxScore(100).percentage((double) score)
            .rank(rank > 0 ? rank : null)
            .themes(themeResults)
            .modelAnswers(modelAnswersList)
            .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CANDIDATE RESULTS — TECHNICAL overview
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

        List<TestSession> allCompleted = testSessionRepository.findByAssessmentId(assessmentId).stream()
            .filter(s -> s.getStatus() == TestSession.SessionStatus.COMPLETED)
            .sorted(Comparator.comparingDouble(s -> -(s.getScore() != null ? s.getScore() : 0)))
            .collect(Collectors.toList());
        int rank = allCompleted.indexOf(session) + 1;

        Integer durationMinutes = null;
        if (session.getStartedAt() != null && session.getCompletedAt() != null) {
            durationMinutes = (int) java.time.Duration
                .between(session.getStartedAt(), session.getCompletedAt()).toMinutes();
        }

        return CandidateTestResultResponse.builder()
            .submissionId(session.getId())
            .candidateId(candidate.getId())
            .candidateName(candidate.getFirstName() + " " + candidate.getLastName())
            .candidateEmail(candidate.getEmail())
            .jobTitle(assessment.getJob().getTitle())
            .testName(assessment.getName()).testId(assessmentId)
            .status(session.getStatus().name())
            .startedAt(session.getStartedAt() != null ? session.getStartedAt().toString() : null)
            .completedAt(session.getCompletedAt() != null ? session.getCompletedAt().toString() : null)
            .durationMinutes(durationMinutes)
            .totalScore(earnedPts).maxScore(totalPts).percentage(pct)
            .rank(rank > 0 ? rank : null)
            .themes(List.of()).modelAnswers(List.of())
            .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CANDIDATE RESULT — generic
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public CandidateTestResultResponse getCandidateResult(String assessmentId, String candidateId) {
        return getTechCandidateResult(assessmentId, candidateId);
    }

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
            Candidate candidate = session.getCandidate();
            boolean isCompleted = session.getStatus() == TestSession.SessionStatus.COMPLETED;
            Integer rank = isCompleted ? completed.indexOf(session) + 1 : null;

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
                .rank(rank)
                .build());
        }

        long completedCount  = sessions.stream()
            .filter(s -> s.getStatus() == TestSession.SessionStatus.COMPLETED).count();
        long inProgressCount = sessions.stream()
            .filter(s -> s.getStatus() == TestSession.SessionStatus.IN_PROGRESS).count();
        long notStarted      = sessions.stream()
            .filter(s -> s.getStatus() == TestSession.SessionStatus.PENDING
                      || s.getStatus() == TestSession.SessionStatus.NOT_STARTED).count();
        double avgScore = completed.isEmpty() ? 0.0
            : completed.stream().mapToDouble(this::safeScore).average().orElse(0.0);

        return TestCandidatesResponse.builder()
            .testId(assessment.getId())
            .testName(assessment.getName())
            .jobTitle(assessment.getJob().getTitle())
            .department(assessment.getJob().getDepartment())
            .totalInvited(sessions.size())
            .completed((int) completedCount)
            .inProgress((int) inProgressCount)
            .notStarted((int) notStarted)
            .avgScore(Math.round(avgScore * 10.0) / 10.0)
            .candidates(summaries)
            .build();
    }

    /** Alias for RH candidates — uses same unified session. */
    @Transactional(readOnly = true)
    public TestCandidatesResponse getRhCandidatesForTest(String assessmentId) {
        return getCandidatesForTest(assessmentId);
    }

    /** Alias for Tech candidates — uses same unified session. */
    @Transactional(readOnly = true)
    public TestCandidatesResponse getTechCandidatesForTest(String assessmentId) {
        return getCandidatesForTest(assessmentId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CANDIDATE FULL RESULT (technical — detailed answers)
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public CandidateFullResultResponse getCandidateFullResult(String assessmentId, String candidateId) {

        log.info("╔══════════════════════════════════════════════════════════════");
        log.info("║ getCandidateFullResult — assessmentId={} candidateId={}", assessmentId, candidateId);
        log.info("╚══════════════════════════════════════════════════════════════");

        Assessment assessment = findAssessment(assessmentId);

        TestSession session = testSessionRepository
            .findByCandidateIdAndAssessmentId(candidateId, assessmentId)
            .orElseThrow(() -> new RuntimeException(
                "Session not found for assessmentId=" + assessmentId + " candidateId=" + candidateId));

        String rawJson = session.getAnswersJson();
        Map<String, Object> answersMap = parseSavedAnswers(rawJson);

        List<ThemeAnswersResponse> themeAnswers = new ArrayList<>();
        int totalEarned = 0;
        int totalMax    = 0;

        if (!answersMap.isEmpty()) {
            List<String> questionIds = new ArrayList<>(answersMap.keySet());
            List<Question> questions = questionRepository.findByIdIn(questionIds);

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
                    .themeCategory("LOGIC").totalScore(sessionEarned)
                    .maxScore(sessionTotal).percentage(pct)
                    .questions(Collections.emptyList()).build());
                totalEarned = sessionEarned;
                totalMax    = sessionTotal;
            }

            for (Map.Entry<String, List<Question>> entry : byTheme.entrySet()) {
                List<Question> themeQs = entry.getValue();
                themeQs.sort(Comparator.comparingInt(q -> q.getOrderIndex() != null ? q.getOrderIndex() : 0));

                String themeName     = assessment.getName();
                String themeCategory = "LOGIC";
                String themeId       = entry.getKey();

                if (!themeQs.isEmpty() && themeQs.get(0).getTheme() != null) {
                    TestTheme th = themeQs.get(0).getTheme();
                    if (th.getName()     != null) themeName     = th.getName();
                    if (th.getCategory() != null) themeCategory = th.getCategory().name();
                    themeId = th.getId();
                }

                List<QuestionAnswerResponse> qResponses = new ArrayList<>();
                int themeEarned = 0, themeMax = 0;

                for (Question q : themeQs) {
                    QuestionAnswerResponse qr = buildQuestionAnswerResponse(q, answersMap.get(q.getId()));
                    qResponses.add(qr);
                    themeEarned += qr.getEarnedPoints();
                    themeMax    += qr.getPoints();
                }

                totalEarned += themeEarned;
                totalMax    += themeMax;
                double themePct = themeMax > 0
                    ? Math.round((double) themeEarned / themeMax * 1000.0) / 10.0 : 0.0;

                themeAnswers.add(ThemeAnswersResponse.builder()
                    .themeId(themeId).themeName(themeName).themeCategory(themeCategory)
                    .totalScore(themeEarned).maxScore(themeMax).percentage(themePct)
                    .questions(qResponses).build());
            }
        }

        List<TestSession> allCompleted = testSessionRepository.findByAssessmentId(assessmentId).stream()
            .filter(s -> s.getStatus() == TestSession.SessionStatus.COMPLETED)
            .sorted(Comparator.comparingDouble(s -> -(s.getScore() != null ? s.getScore() : 0)))
            .collect(Collectors.toList());
        int rank = allCompleted.indexOf(session) + 1;

        Integer durationMinutes = null;
        if (session.getStartedAt() != null && session.getCompletedAt() != null) {
            durationMinutes = (int) java.time.Duration
                .between(session.getStartedAt(), session.getCompletedAt()).toMinutes();
        }

        int finalEarned = session.getEarnedPoints() != null ? session.getEarnedPoints() : totalEarned;
        int finalMax    = session.getTotalPoints()  != null ? session.getTotalPoints()  : totalMax;
        double finalPct = finalMax > 0
            ? Math.round((double) finalEarned / finalMax * 1000.0) / 10.0 : 0.0;

        Candidate candidate = session.getCandidate();
        return CandidateFullResultResponse.builder()
            .submissionId(session.getId())
            .candidateId(candidate.getId())
            .candidateName(candidate.getFirstName() + " " + candidate.getLastName())
            .candidateEmail(candidate.getEmail())
            .jobTitle(assessment.getJob().getTitle())
            .testName(assessment.getName()).testId(assessmentId)
            .status(session.getStatus().name())
            .startedAt(session.getStartedAt() != null ? session.getStartedAt().toString() : null)
            .completedAt(session.getCompletedAt() != null ? session.getCompletedAt().toString() : null)
            .durationMinutes(durationMinutes)
            .totalScore(finalEarned).maxScore(finalMax).percentage(finalPct)
            .rank(rank > 0 ? rank : null)
            .themes(List.of())
            .themeAnswers(themeAnswers)
            .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PRIVATE — QUESTION SCORING
    // ══════════════════════════════════════════════════════════════════════════

    private int scoreQuestion(Question q, Object rawAnswer,
                              Map<String, Object> answers, SubmitTestRequest req) {
        if (rawAnswer == null) return 0;
        int max = q.getPoints() != null ? q.getPoints() : 10;

        try {
            if (q.getKind() == Question.QuestionKind.PROBLEM_SOLVING) {
                return scoreProblemSolving(q, rawAnswer, answers, max);
            } else {
                return scoreQcm(q, rawAnswer, max);
            }
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
            String json = objectMapper.writeValueAsString(rawAnswer);
            ans = objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) { return 0; }

        String code     = (String) ans.getOrDefault("code", "");
        String language = (String) ans.getOrDefault("language", "python");

        if (code == null || code.isBlank()) return 0;

        List<RunCodeRequest.TestCasePayload> cases = (q.getTestCases() != null)
                ? q.getTestCases().stream()
                    .map(tc -> new RunCodeRequest.TestCasePayload(
                        tc.getInput(), tc.getOutput(), tc.getPoints(), tc.isVisible()))
                    .collect(Collectors.toList())
                : List.of();

        if (cases.isEmpty()) return 0;

        List<TestCaseResultDto> results = codeExecutionService.execute(code, language, cases);

        int earned = results.stream()
                .filter(TestCaseResultDto::isPassed)
                .mapToInt(TestCaseResultDto::getEarnedPoints)
                .sum();

        List<Map<String, Object>> testResultsToSave = results.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("input",        r.getInput());
            m.put("expected",     r.getExpected());
            m.put("actual",       r.getActual());
            m.put("passed",       r.isPassed());
            m.put("points",       r.getPoints());
            m.put("earnedPoints", r.getEarnedPoints());
            m.put("executionMs",  r.getExecutionMs());
            m.put("isVisible",    true);
            m.put("error",        r.getError());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> enrichedAns = new LinkedHashMap<>(ans);
        enrichedAns.put("testResults", testResultsToSave);
        answers.put(q.getId(), enrichedAns);

        log.info("Question {} — code scored: {}/{} pts ({} test cases)",
                q.getId(), earned, maxPts, results.size());

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
                    String json = objectMapper.writeValueAsString(rawAnswer);
                    selected = objectMapper.readValue(json, new TypeReference<>() {});
                } catch (Exception e) { yield 0; }
                yield opts.stream()
                        .filter(o -> selected.contains(o.getId()) || selected.contains(o.getText()))
                        .mapToInt(o -> o.getPoints() != null ? o.getPoints() : 0)
                        .sum();
            }
            case "LIKERT" -> {
                int val = Integer.parseInt(rawAnswer.toString());
                int idx = val - 1;
                if (q.getLikertPoints() != null && idx >= 0 && idx < q.getLikertPoints().size()) {
                    yield q.getLikertPoints().get(idx);
                }
                yield 0;
            }
            default -> 0;
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PRIVATE — ANSWER BUILDER (for full result responses)
    // ══════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private QuestionAnswerResponse buildQuestionAnswerResponse(Question q, Object rawAnswer) {

        QuestionAnswerResponse.QuestionAnswerResponseBuilder b = QuestionAnswerResponse.builder()
            .questionId(q.getId())
            .title(q.getTitle() != null ? q.getTitle() : "")
            .statement(stripHtml(q.getStatement() != null ? q.getStatement() : q.getText()))
            .type(q.getKind() != null ? q.getKind().name() : "QCM")
            .questionType(q.getQuestionType() != null ? q.getQuestionType().name() : "RADIO")
            .complexity(q.getComplexity())
            .timeLimit(q.getTimeLimit())
            .memoryLimit(q.getMemoryLimit())
            .points(q.getPoints() != null ? q.getPoints() : 0)
            .orderIndex(q.getOrderIndex() != null ? q.getOrderIndex() : 0);

        if (q.getKind() == Question.QuestionKind.PROBLEM_SOLVING) {
            String code     = null;
            String language = null;
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
                                .input((String) tc.get("input"))
                                .expectedOutput((String) tc.get("expected"))
                                .actualOutput((String) tc.get("actual"))
                                .passed(Boolean.TRUE.equals(tc.get("passed")))
                                .points(toInt(tc.get("points")))
                                .earnedPoints(toInt(tc.get("earnedPoints")))
                                .executionMs(toLong(tc.get("executionMs")))
                                .isVisible(Boolean.TRUE.equals(tc.get("isVisible")))
                                .build());
                        }
                    }
                }
            }

            if (tcResults.isEmpty() && q.getTestCases() != null) {
                for (Question.TestCase tc : q.getTestCases()) {
                    tcResults.add(TestCaseAnswerResponse.builder()
                        .input(tc.getInput()).expectedOutput(tc.getOutput())
                        .actualOutput(null).passed(false)
                        .points(tc.getPoints() != null ? tc.getPoints() : 0)
                        .earnedPoints(0).executionMs(null).isVisible(tc.isVisible())
                        .build());
                }
            }

            int earned = tcResults.stream().mapToInt(TestCaseAnswerResponse::getEarnedPoints).sum();
            return b.submittedCode(code).submittedLanguage(language)
                    .testCases(tcResults).options(Collections.emptyList()).earnedPoints(earned).build();
        }

        if (q.getQuestionType() == Question.QuestionType.LIKERT) {
            Integer selectedLikert = null;
            int earned = 0;
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
        if (rawAnswer instanceof String str) {
            selectedIds.add(str);
        } else if (rawAnswer instanceof List<?> list) {
            for (Object o : list) { if (o instanceof String s) selectedIds.add(s); }
        }

        List<QcmOptionAnswerResponse> optionResponses = new ArrayList<>();
        int earned = 0;
        List<Question.QcmOption> opts = q.getQcmOptions() != null ? q.getQcmOptions() : Collections.emptyList();
        for (Question.QcmOption opt : opts) {
            boolean selected = opt.getId() != null && selectedIds.contains(opt.getId());
            int optPts = opt.getPoints() != null ? opt.getPoints() : 0;
            if (selected) earned += optPts;
            optionResponses.add(QcmOptionAnswerResponse.builder()
                .id(opt.getId()).text(opt.getText()).optionText(opt.getText())
                .isCorrect(opt.isCorrect()).points(optPts).selected(selected)
                .build());
        }

        return b.options(optionResponses).earnedPoints(earned)
                .testCases(Collections.emptyList())
                .likertPoints(q.getLikertPoints() != null ? q.getLikertPoints() : Collections.emptyList())
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PRIVATE — MAPPERS
    // ══════════════════════════════════════════════════════════════════════════

    private JobTestResponse mapAssessmentSummary(Assessment a) {
        return JobTestResponse.builder()
            .id(a.getId()).jobId(a.getJob().getId())
            .jobTitle(a.getJob().getTitle()).department(a.getJob().getDepartment())
            .name(a.getName()).description(a.getDescription())
            .status(a.getStatus().name()).themesCount(a.getThemes().size())
            .questionsCount(countAllQuestions(a)).candidatesCount(0).completionRate(0)
            .createdAt(a.getCreatedAt()).themes(Collections.emptyList())
            .build();
    }

    private JobTestResponse mapAssessmentFull(Assessment a) {
        return JobTestResponse.builder()
            .id(a.getId()).jobId(a.getJob().getId())
            .jobTitle(a.getJob().getTitle()).department(a.getJob().getDepartment())
            .name(a.getName()).description(a.getDescription())
            .status(a.getStatus().name()).themesCount(a.getThemes().size())
            .questionsCount(countAllQuestions(a)).candidatesCount(0).completionRate(0)
            .createdAt(a.getCreatedAt())
            .themes(a.getThemes().stream().map(this::mapTheme).collect(Collectors.toList()))
            .build();
    }

    private ThemeResponse mapTheme(TestTheme th) {
        List<SimpleQuestionResponse> simpleQs = questionRepository
            .findByThemeIdOrderByOrderIndex(th.getId())
            .stream().map(this::mapSimpleQuestion).collect(Collectors.toList());
        List<ThemeModelResponse> models = th.getThemeModels().stream()
            .map(this::mapThemeModel).collect(Collectors.toList());
        String frontType = (th.getCategory() == TestTheme.ThemeCategory.LOGIC) ? "PROBLEM_SOLVING" : "QCM";
        return ThemeResponse.builder()
            .id(th.getId()).name(th.getName())
            .category(th.getCategory() != null ? th.getCategory().name() : "CUSTOM")
            .type(frontType).orderIndex(th.getOrderIndex() != null ? th.getOrderIndex() : 0)
            .models(models).questions(simpleQs).build();
    }

    private ThemeModelResponse mapThemeModel(ThemeModel tm) {
        return ThemeModelResponse.builder()
            .id(tm.getId()).modelId(tm.getModel().getId())
            .modelName(tm.getModel().getName()).modelDescription(tm.getModel().getDescription())
            .scoringType(tm.getModel().getScoringType().name())
            .weight(tm.getWeight() != null ? tm.getWeight() : 50)
            .dimensions(tm.getModel().getDimensions().stream()
                .sorted(Comparator.comparingInt(d -> d.getOrderIndex() != null ? d.getOrderIndex() : 0))
                .map(d -> DimensionResponse.builder()
                    .id(d.getId()).name(d.getName()).code(d.getCode()).color(d.getColor()).build())
                .collect(Collectors.toList()))
            .questions(tm.getQuestions().stream().map(this::mapPsychometricQuestion).collect(Collectors.toList()))
            .build();
    }

    private QuestionResponse mapPsychometricQuestion(Question q) {
        String imageUrl = q.getImagePath() != null
            ? baseUrl + "/api/v1/job-tests/questions/" + q.getId() + "/image" : null;
        return QuestionResponse.builder()
            .id(q.getId()).text(q.getText()).imageUrl(imageUrl)
            .type(q.getQuestionType() != null ? q.getQuestionType().name() : "RADIO")
            .orderIndex(q.getOrderIndex() != null ? q.getOrderIndex() : 0)
            .options(q.getOptions().stream()
                .sorted(Comparator.comparingInt(o -> o.getOrderIndex() != null ? o.getOrderIndex() : 0))
                .map(o -> OptionResponse.builder()
                    .id(o.getId()).text(o.getText()).dimensionId(o.getDimensionId())
                    .points(o.getPoints()).orderIndex(o.getOrderIndex() != null ? o.getOrderIndex() : 0).build())
                .collect(Collectors.toList()))
            .build();
    }

    private SimpleQuestionResponse mapSimpleQuestion(Question q) {
        String imageUrl = q.getImagePath() != null
            ? baseUrl + "/api/v1/job-tests/simple-questions/" + q.getId() + "/image" : null;
        return SimpleQuestionResponse.builder()
            .id(q.getId()).title(q.getTitle()).statement(q.getStatement()).points(q.getPoints())
            .orderIndex(q.getOrderIndex() != null ? q.getOrderIndex() : 0)
            .type(q.getKind() != null ? q.getKind().name() : "QCM")
            .complexity(q.getComplexity()).timeLimit(q.getTimeLimit()).memoryLimit(q.getMemoryLimit())
            .testCases(q.getTestCases() != null
                ? q.getTestCases().stream().map(tc -> TestCaseResponse.builder()
                    .input(tc.getInput()).output(tc.getOutput()).points(tc.getPoints()).build())
                    .collect(Collectors.toList()) : Collections.emptyList())
            .questionType(q.getQuestionType() != null ? q.getQuestionType().name() : null)
            .options(q.getQcmOptions() != null
                ? q.getQcmOptions().stream().map(o -> QcmOptionResponse.builder()
                    .text(o.getText()).correct(o.isCorrect()).points(o.getPoints()).build())
                    .collect(Collectors.toList()) : Collections.emptyList())
            .likertPoints(q.getLikertPoints()).imageUrl(imageUrl).build();
    }

    private SimpleQuestionDto mapToTechnicalDto(Question q, Object savedAnswer) {
        List<TestCaseDto> cases = null;
        if (q.getTestCases() != null) {
            cases = q.getTestCases().stream()
                    .map(tc -> TestCaseDto.builder()
                            .input(tc.getInput()).output(tc.getOutput())
                            .points(tc.getPoints() != null ? tc.getPoints() : 10)
                            .isVisible(tc.isVisible())
                            .build())
                    .collect(Collectors.toList());
        }

        List<QcmOptionDto> options = null;
        if (q.getQcmOptions() != null) {
            List<Question.QcmOption> shuffled = new ArrayList<>(q.getQcmOptions());
            Collections.shuffle(shuffled);
            final int[] idx = {0};
            options = shuffled.stream()
                    .map(o -> QcmOptionDto.builder()
                            .id("opt-" + idx[0]++)
                            .text(o.getText())
                            .points(o.getPoints() != null ? o.getPoints() : 0)
                            .build())
                    .collect(Collectors.toList());
        }

        return SimpleQuestionDto.builder()
                .id(q.getId())
                .title(q.getTitle())
                .statement(q.getStatement())
                .points(q.getPoints() != null ? q.getPoints() : 10)
                .orderIndex(q.getOrderIndex() != null ? q.getOrderIndex() : 0)
                .type(q.getKind() != null ? q.getKind().name() : "QCM")
                .complexity(q.getComplexity())
                .timeLimit(q.getTimeLimit())
                .memoryLimit(q.getMemoryLimit())
                .testCases(cases)
                .supportedLangs(List.of("python", "javascript", "java", "c", "cpp", "go"))
                .selectedLanguage("python")
                .questionType(q.getQuestionType() != null ? q.getQuestionType().name() : null)
                .options(options)
                .likertPoints(q.getLikertPoints())
                .imageUrl(q.getImagePath() != null
                        ? baseUrl + "/api/v1/job-tests/simple-questions/" + q.getId() + "/image"
                        : null)
                .savedAnswer(savedAnswer)
                .build();
    }

    private com.nexgenai.dto.test.QuestionDto mapRhQuestion(Question q) {
        String imageUrl = null;
        if (q.getImagePath() != null) {
            imageUrl = baseUrl + "/api/v1/job-tests/questions/" + q.getId() + "/image";
        }

        List<com.nexgenai.dto.test.OptionDto> options = new ArrayList<>(q.getOptions()).stream()
                .sorted(Comparator.comparingInt(o -> o.getOrderIndex() != null ? o.getOrderIndex() : 0))
                .map(o -> com.nexgenai.dto.test.OptionDto.builder()
                        .id(o.getId())
                        .text(o.getText())
                        .orderIndex(o.getOrderIndex() != null ? o.getOrderIndex() : 0)
                        .build())
                .collect(Collectors.toList());

        return com.nexgenai.dto.test.QuestionDto.builder()
                .id(q.getId())
                .text(q.getText())
                .imageUrl(imageUrl)
                .kind(q.getKind() != null ? q.getKind().name() : "QCM")              // ✅ was missing
                .questionType(q.getQuestionType() != null ? q.getQuestionType().name() : "RADIO")  // ✅ fixed
                .orderIndex(q.getOrderIndex() != null ? q.getOrderIndex() : 0)
                .options(options)
                .build();
    }

    private ModelResponse mapModel(PsychometricModel m) {
        return ModelResponse.builder()
            .id(m.getId()).name(m.getName()).description(m.getDescription())
            .scoringType(m.getScoringType().name()).builtIn(m.isBuiltIn())
            .dimensions(m.getDimensions().stream()
                .sorted(Comparator.comparingInt(d -> d.getOrderIndex() != null ? d.getOrderIndex() : 0))
                .map(d -> DimensionResponse.builder()
                    .id(d.getId()).name(d.getName()).code(d.getCode()).color(d.getColor()).build())
                .collect(Collectors.toList()))
            .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PRIVATE — BUILDERS & HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private Question buildQuestion(SaveFullTestRequest.QuestionPayload qp, TestTheme theme, String type) {
        Question.QuestionKind kind = "PROBLEM_SOLVING".equalsIgnoreCase(type)
            ? Question.QuestionKind.PROBLEM_SOLVING : Question.QuestionKind.QCM;

        Question.QuestionBuilder builder = Question.builder()
            .theme(theme)
            .assessment(theme.getAssessment())
            .kind(kind)
            .title(qp.getTitle())
            .statement(qp.getStatement())
            .text(qp.getStatement())
            .points(qp.getPoints())
            .orderIndex(qp.getOrderIndex());

        if (kind == Question.QuestionKind.PROBLEM_SOLVING) {
            builder.complexity(qp.getComplexity())
                   .timeLimit(qp.getTimeLimit())
                   .memoryLimit(qp.getMemoryLimit());
            if (qp.getTestCases() != null) {
                builder.testCases(mapTestCases(qp.getTestCases()));
            }
        } else {
            if (qp.getQuestionType() != null) {
                builder.questionType(Question.QuestionType.valueOf(qp.getQuestionType().toUpperCase()));
            }
            if (qp.getOptions() != null) {
                builder.qcmOptions(mapQcmOptions(qp.getOptions()));
            }
            if (qp.getLikertPoints() != null) {
                builder.likertPoints(qp.getLikertPoints());
            }
        }

        return builder.build();
    }

    private TestTheme resolveOrCreateTheme(SaveFullTestRequest.ThemePayload thPayload, Assessment assessment) {
        if (thPayload.getId() != null) {
            Optional<TestTheme> existing = themeRepository.findById(thPayload.getId());
            if (existing.isPresent()) {
                TestTheme theme = existing.get();
                if (thPayload.getName() != null) theme.setName(thPayload.getName());
                if (thPayload.getType() != null) {
                    theme.setCategory(mapTypeToCategory(thPayload.getType()).equals("LOGIC")
                        ? TestTheme.ThemeCategory.LOGIC : TestTheme.ThemeCategory.CUSTOM);
                }
                theme.setOrderIndex(thPayload.getOrderIndex());
                return themeRepository.save(theme);
            }
        }
        TestTheme newTheme = TestTheme.builder()
            .assessment(assessment)
            .name(thPayload.getName() != null ? thPayload.getName() : "Theme")
            .category(mapTypeToCategory(thPayload.getType()).equals("LOGIC")
                ? TestTheme.ThemeCategory.LOGIC : TestTheme.ThemeCategory.CUSTOM)
            .orderIndex(thPayload.getOrderIndex())
            .build();
        return themeRepository.save(newTheme);
    }

    private int countAllQuestions(Assessment a) {
        int count = 0;
        for (TestTheme th : a.getThemes()) {
            count += questionRepository.countByThemeId(th.getId());
            for (ThemeModel tm : th.getThemeModels()) {
                count += tm.getQuestions().size();
            }
        }
        return count;
    }

    private Assessment findAssessment(String id) {
        return assessmentRepository.findByIdWithThemes(id)
            .orElseThrow(() -> new RuntimeException("Assessment not found: " + id));
    }

    private Job findJob(String id) {
        return jobRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Job not found: " + id));
    }

    private double safeScore(TestSession s) {
        if (s.getTotalPoints() == null || s.getTotalPoints() == 0) return 0;
        int earned = s.getEarnedPoints() != null ? s.getEarnedPoints() : 0;
        return Math.round((double) earned / s.getTotalPoints() * 1000.0) / 10.0;
    }

    private List<Question.TestCase> mapTestCases(List<SaveFullTestRequest.TestCasePayload> tcs) {
        if (tcs == null) return Collections.emptyList();
        return tcs.stream().map(tc -> new Question.TestCase(
            tc.getInput(), tc.getOutput(),
            tc.getPoints() != null ? tc.getPoints() : 10,
            tc.getVisible() != null ? tc.getVisible() : true))
            .collect(Collectors.toList());
    }

    private List<Question.QcmOption> mapQcmOptions(List<SaveFullTestRequest.QcmOptionPayload> opts) {
        if (opts == null) return Collections.emptyList();
        return opts.stream().map(o -> new Question.QcmOption(
            null, o.getText(), o.isCorrect(), o.getPoints() != null ? o.getPoints() : 0))
            .collect(Collectors.toList());
    }

    private String mapTypeToCategory(String frontendType) {
        if (frontendType == null) return "CUSTOM";
        return switch (frontendType.toUpperCase()) {
            case "PROBLEM_SOLVING" -> "LOGIC";
            default                -> "CUSTOM";
        };
    }

    private PsychometricModel buildModel(String name, String desc,
                                          PsychometricModel.ScoringType scoring,
                                          List<ModelDimension> dims) {
        PsychometricModel m = PsychometricModel.builder()
            .name(name).description(desc).scoringType(scoring).builtIn(true).build();
        dims.forEach(m::addDimension);
        return m;
    }

    private ModelDimension dim(String name, String code, String color, int order) {
        return ModelDimension.builder().name(name).code(code).color(color).orderIndex(order).build();
    }

    private AntiCheatRiskLevel computeRiskLevel(List<AntiCheatEventDto> events) {
        if (events == null || events.isEmpty()) return AntiCheatRiskLevel.LOW;
        long tabSwitches = events.stream().filter(e -> "TAB_SWITCH".equals(e.getType())).count();
        long pastes = events.stream().filter(e -> "PASTE".equals(e.getType())).count();
        long devTools = events.stream()
            .filter(e -> "DEV_TOOLS".equals(e.getType())
                      || "DEVTOOLS_ATTEMPT".equals(e.getType())
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
                    .filter(item -> item instanceof Map<?,?> tc &&
                        Boolean.TRUE.equals(((Map<?,?>) tc).get("passed")))
                    .mapToInt(item -> {
                        Object ep = ((Map<?,?>) item).get("earnedPoints");
                        return ep instanceof Number n ? n.intValue() : 0;
                    }).sum();
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSavedAnswers(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) { return new LinkedHashMap<>(); }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseAntiCheatLog(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) { return new ArrayList<>(); }
    }

    @SuppressWarnings("unchecked")
    private List<AntiCheatEventDto> parseAntiCheatEvents(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return ".jpg";
        return filename.substring(filename.lastIndexOf('.'));
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
