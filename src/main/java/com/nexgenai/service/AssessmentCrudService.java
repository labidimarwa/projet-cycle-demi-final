package com.nexgenai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexgenai.dto.jobtest.JobTestDtos.*;
import com.nexgenai.model.*;
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
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the definition of assessments: CRUD for tests, themes, questions, and models.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssessmentCrudService {

    private final AssessmentRepository        assessmentRepository;
    private final TestThemeRepository         themeRepository;
    private final ThemeModelRepository        themeModelRepository;
    private final PsychometricModelRepository modelRepository;
    private final QuestionRepository          questionRepository;
    private final QuestionOptionRepository    optionRepository;
    private final JobRepository               jobRepository;
    private final TestSessionRepository       testSessionRepository;
    private final WorkflowStageRepository     workflowStageRepository;

    private final ObjectMapper objectMapper;

    @Value("${app.question.image-dir:uploads/questions}")
    private String imageDir;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    // ══════════════════════════════════════════════════════════════════════════
    // JOBS WITH TESTS
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<JobWithTestsResponse> getJobsWithTests(String role, String evaluatorId) {
        List<Job> jobs = jobRepository.findAll();
        List<JobWithTestsResponse> result = new ArrayList<>();

        for (Job job : jobs) {
            List<TestSummaryResponse> allTests = new ArrayList<>();
            List<Assessment> assessments = (evaluatorId != null)
                ? assessmentRepository.findByJobIdAndAssigneeId(job.getId(), evaluatorId)
                : assessmentRepository.findByJobId(job.getId());

            if ("HR".equals(role)) {
                assessments = assessments.stream()
                    .filter(a -> a.getType() == AssessmentType.RH)
                    .collect(Collectors.toList());
            }

            for (Assessment a : assessments) {
                String stageTypeName = null;
                if (a.getWorkflowStageId() != null && job.getWorkflowStages() != null) {
                    stageTypeName = job.getWorkflowStages().stream()
                        .filter(s -> s.getId().equals(a.getWorkflowStageId()))
                        .map(s -> s.getStageType() != null ? s.getStageType().name() : null)
                        .findFirst().orElse(null);
                }
                allTests.add(TestSummaryResponse.builder()
                    .id(a.getId()).name(a.getName()).description(a.getDescription())
                    .status(a.getStatus().name())
                    .themesCount(a.getThemes() != null ? a.getThemes().size() : 0)
                    .questionsCount(countAllQuestions(a))
                    .candidatesCount(testSessionRepository.countByAssessmentId(a.getId()))
                    .completionRate(0)
                    .createdAt(a.getCreatedAt() != null ? a.getCreatedAt().toString() : null)
                    .stageType(stageTypeName).source("ASSESSMENT").build());
            }

            if (!allTests.isEmpty()) {
                result.add(JobWithTestsResponse.builder()
                    .id(job.getId()).title(job.getTitle()).department(job.getDepartment())
                    .location(job.getLocation()).status(job.getStatus().name())
                    .tests(allTests).build());
            }
        }
        return result;
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
            .filter(s -> s.getId().equals(workflowStageId)).findFirst()
            .orElseThrow(() -> new RuntimeException("Stage not found"));

        AssessmentType type = stage.getStageType() == StageType.RH_TEST
            ? AssessmentType.RH : AssessmentType.TECHNICAL;

        Assessment assessment = Assessment.builder()
            .job(job).name(stage.getName()).description(stage.getDescription())
            .type(type).status(Assessment.AssessmentStatus.DRAFT)
            .workflowStageId(workflowStageId).build();

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
            .type(AssessmentType.TECHNICAL).status(Assessment.AssessmentStatus.DRAFT).build();
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
            .filter(id -> !incomingThemeIds.contains(id)).collect(Collectors.toList());

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
    // PSYCHOMETRIC QUESTIONS
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public QuestionResponse addPsychometricQuestion(String themeModelId, CreateQuestionRequest req) {
        ThemeModel tm = themeModelRepository.findById(themeModelId)
            .orElseThrow(() -> new RuntimeException("ThemeModel not found"));
        Question q = Question.builder()
            .text(req.getText()).kind(Question.QuestionKind.QCM)
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
        Files.copy(file.getInputStream(), dir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
        q.setImagePath(dir.resolve(filename).toString());
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
    // SIMPLE / TECHNICAL QUESTIONS
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
        log.info("Built-in psychometric models initialized");
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
            .createdAt(a.getCreatedAt()).themes(Collections.emptyList()).build();
    }

    public JobTestResponse mapAssessmentFull(Assessment a) {
        return JobTestResponse.builder()
            .id(a.getId()).jobId(a.getJob().getId())
            .jobTitle(a.getJob().getTitle()).department(a.getJob().getDepartment())
            .name(a.getName()).description(a.getDescription())
            .status(a.getStatus().name())
            .type(a.getType() != null ? a.getType().name() : "RH")
            .themesCount(a.getThemes().size())
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

    public QuestionResponse mapPsychometricQuestion(Question q) {
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

    public SimpleQuestionResponse mapSimpleQuestion(Question q) {
        String imageUrl = q.getImagePath() != null
            ? baseUrl + "/api/v1/job-tests/simple-questions/" + q.getId() + "/image" : null;
        return SimpleQuestionResponse.builder()
            .id(q.getId()).title(q.getTitle()).statement(q.getStatement()).points(q.getPoints())
            .orderIndex(q.getOrderIndex() != null ? q.getOrderIndex() : 0)
            .type(q.getKind() != null ? q.getKind().name() : "QCM")
            .complexity(q.getComplexity()).timeLimit(q.getTimeLimit()).memoryLimit(q.getMemoryLimit())
            .testCases(q.getTestCases() != null
                ? q.getTestCases().stream().map(tc -> TestCaseResponse.builder()
                    .input(tc.getInput()).output(tc.getOutput()).points(tc.getPoints())
                    .visible(tc.isVisible()).build())
                    .collect(Collectors.toList()) : Collections.emptyList())
            .supportedLangs(q.getSupportedLangs())
            .questionType(q.getQuestionType() != null ? q.getQuestionType().name() : null)
            .options(q.getQcmOptions() != null
                ? q.getQcmOptions().stream().map(o -> QcmOptionResponse.builder()
                    .text(o.getText()).correct(o.isCorrect()).points(o.getPoints()).build())
                    .collect(Collectors.toList()) : Collections.emptyList())
            .likertPoints(q.getLikertPoints()).imageUrl(imageUrl).build();
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
            .theme(theme).assessment(theme.getAssessment()).kind(kind)
            .title(qp.getTitle()).statement(qp.getStatement()).text(qp.getStatement())
            .points(qp.getPoints()).orderIndex(qp.getOrderIndex());

        if (kind == Question.QuestionKind.PROBLEM_SOLVING) {
            builder.complexity(qp.getComplexity()).timeLimit(qp.getTimeLimit()).memoryLimit(qp.getMemoryLimit());
            if (qp.getTestCases()    != null) builder.testCases(mapTestCases(qp.getTestCases()));
            if (qp.getSupportedLangs() != null && !qp.getSupportedLangs().isEmpty())
                builder.supportedLangs(qp.getSupportedLangs());
        } else {
            if (qp.getQuestionType() != null)
                builder.questionType(Question.QuestionType.valueOf(qp.getQuestionType().toUpperCase()));
            if (qp.getOptions()      != null) builder.qcmOptions(mapQcmOptions(qp.getOptions()));
            if (qp.getLikertPoints() != null) builder.likertPoints(qp.getLikertPoints());
        }
        return builder.build();
    }

    private TestTheme resolveOrCreateTheme(SaveFullTestRequest.ThemePayload thPayload, Assessment assessment) {
        if (thPayload.getId() != null) {
            Optional<TestTheme> existing = themeRepository.findById(thPayload.getId());
            if (existing.isPresent()) {
                TestTheme theme = existing.get();
                if (thPayload.getName() != null) theme.setName(thPayload.getName());
                if (thPayload.getType() != null)
                    theme.setCategory(mapTypeToCategory(thPayload.getType()).equals("LOGIC")
                        ? TestTheme.ThemeCategory.LOGIC : TestTheme.ThemeCategory.CUSTOM);
                theme.setOrderIndex(thPayload.getOrderIndex());
                return themeRepository.save(theme);
            }
        }
        return themeRepository.save(TestTheme.builder()
            .assessment(assessment)
            .name(thPayload.getName() != null ? thPayload.getName() : "Theme")
            .category(mapTypeToCategory(thPayload.getType()).equals("LOGIC")
                ? TestTheme.ThemeCategory.LOGIC : TestTheme.ThemeCategory.CUSTOM)
            .orderIndex(thPayload.getOrderIndex()).build());
    }

    public int countAllQuestions(Assessment a) {
        int count = 0;
        for (TestTheme th : a.getThemes()) {
            count += questionRepository.countByThemeId(th.getId());
            for (ThemeModel tm : th.getThemeModels()) count += tm.getQuestions().size();
        }
        return count;
    }

    public Assessment findAssessment(String id) {
        return assessmentRepository.findByIdWithThemes(id)
            .orElseThrow(() -> new RuntimeException("Assessment not found: " + id));
    }

    private Job findJob(String id) {
        return jobRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Job not found: " + id));
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
        return "PROBLEM_SOLVING".equalsIgnoreCase(frontendType) ? "LOGIC" : "CUSTOM";
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

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return ".jpg";
        return filename.substring(filename.lastIndexOf('.'));
    }
}
