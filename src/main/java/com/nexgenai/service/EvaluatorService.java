package com.nexgenai.service;

import com.nexgenai.dto.evaluator.EvaluatorDashboardDto;
import com.nexgenai.dto.evaluator.EvaluatorDashboardDto.*;
import com.nexgenai.dto.evaluator.EvaluatorSummaryDTO;
import com.nexgenai.model.*;
import com.nexgenai.model.enums.AssessmentType;
import com.nexgenai.model.enums.StageType;
import com.nexgenai.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 2 refactoring: updated to use unified Assessment, TestSession, and
 * Question models + repositories instead of the legacy JobTest,
 * TechnicalSession, and SimpleQuestion types.
 */
@Service
public class EvaluatorService {

    private final TechEvaluatorRepository techEvaluatorRepository;
    private final HRRepository            hrRepository;
    private final UserRepository          userRepository;

    private final InterviewRepository        interviewRepository;
    private final InterviewSlotRepository    slotRepository;
    private final AssessmentRepository       assessmentRepository;   // was JobTestRepository
    private final JobRepository              jobRepository;
    private final TestSessionRepository      testSessionRepository;  // was TechnicalSessionRepository
    private final QuestionRepository         questionRepository;     // was SimpleQuestionRepository

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter ISO_FMT  = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public EvaluatorService(TechEvaluatorRepository techEvaluatorRepository,
                            HRRepository hrRepository,
                            UserRepository userRepository,
                            InterviewRepository interviewRepository,
                            InterviewSlotRepository slotRepository,
                            AssessmentRepository assessmentRepository,
                            JobRepository jobRepository,
                            TestSessionRepository testSessionRepository,
                            QuestionRepository questionRepository) {
        this.techEvaluatorRepository = techEvaluatorRepository;
        this.hrRepository            = hrRepository;
        this.userRepository          = userRepository;
        this.interviewRepository     = interviewRepository;
        this.slotRepository          = slotRepository;
        this.assessmentRepository    = assessmentRepository;
        this.jobRepository           = jobRepository;
        this.testSessionRepository   = testSessionRepository;
        this.questionRepository      = questionRepository;
    }

    /**
     * Returns TechEvaluators whose specialization matches the job's department.
     * Falls back to a broader LIKE search if exact match returns nothing.
     */
    public List<EvaluatorSummaryDTO> getEvaluatorsByDepartment(String department) {
        List<TechEvaluator> evaluators = techEvaluatorRepository.findBySpecialization(department);

        // Fallback: broader search if exact match is empty
        if (evaluators.isEmpty()) {
            evaluators = techEvaluatorRepository.findBySpecializationContaining(department);
        }

        return evaluators.stream()
            .map(this::toSummary)
            .collect(Collectors.toList());
    }




    // ── Helper ────────────────────────────────────────────────────────────────

    private EvaluatorSummaryDTO toSummary(TechEvaluator t) {
        EvaluatorSummaryDTO dto = new EvaluatorSummaryDTO();
        dto.setId(t.getId());
        dto.setFullName(t.getFirstName() + " " + t.getLastName());
        dto.setEmail(t.getEmail());
        dto.setDepartment(t.getSpecialization());
        dto.setTitle(t.getTitle());
        dto.setExpertiseLevel(t.getExpertiseLevel());
        dto.setRole("TECH_EVALUATOR");
        return dto;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public EvaluatorDashboardDto buildDashboard(String email) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));

        String evaluatorId = user.getId();

        // ── 1. Fetch all interview slots assigned to this evaluator ───────────
        List<InterviewSlot> mySlots = fetchEvaluatorSlots(evaluatorId);

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        // Today's slots
        List<InterviewSlot> todaySlots = mySlots.stream()
                .filter(s -> s.getSlotStart() != null
                          && s.getSlotStart().toLocalDate().equals(today))
                .sorted(Comparator.comparing(InterviewSlot::getSlotStart))
                .collect(Collectors.toList());

        // Upcoming slots (next 7 days, not today)
        LocalDateTime weekEnd = now.plusDays(7);
        List<InterviewSlot> upcomingSlots = mySlots.stream()
                .filter(s -> s.getSlotStart() != null
                          && s.getSlotStart().isAfter(now)
                          && !s.getSlotStart().toLocalDate().equals(today)
                          && s.getSlotStart().isBefore(weekEnd))
                .sorted(Comparator.comparing(InterviewSlot::getSlotStart))
                .collect(Collectors.toList());

        // Recently completed (last 30 days, max 6)
        LocalDateTime thirtyDaysAgo = now.minusDays(30);
        List<InterviewSlot> recentCompleted = mySlots.stream()
                .filter(s -> InterviewSlot.SlotStatus.COMPLETED.equals(s.getStatus())
                          && s.getSlotStart() != null
                          && s.getSlotStart().isAfter(thirtyDaysAgo))
                .sorted(Comparator.comparing(InterviewSlot::getSlotStart, Comparator.reverseOrder()))
                .limit(6)
                .collect(Collectors.toList());

        // This week stats
        LocalDateTime weekStart = now.minusDays(7);
        List<InterviewSlot> thisWeekCompleted = mySlots.stream()
                .filter(s -> InterviewSlot.SlotStatus.COMPLETED.equals(s.getStatus())
                          && s.getSlotStart() != null
                          && s.getSlotStart().isAfter(weekStart))
                .collect(Collectors.toList());

        // Next interview today
        String nextInterviewTime = todaySlots.stream()
                .filter(s -> !InterviewSlot.SlotStatus.COMPLETED.equals(s.getStatus())
                          && s.getSlotStart() != null
                          && s.getSlotStart().isAfter(now))
                .findFirst()
                .map(s -> s.getSlotStart().format(TIME_FMT))
                .orElse(null);

        // ── 2. Fetch technical tests assigned to this evaluator ───────────────
        List<AssignedTestDto> assignedTests = buildAssignedTests(evaluatorId);

        // ── 3. Pipeline ───────────────────────────────────────────────────────
        PipelineDto pipeline = buildPipeline(mySlots, assignedTests);

        // Pending reviews = tech sessions COMPLETED but slot decision still PENDING
        int pendingReviews = (int) mySlots.stream()
                .filter(s -> InterviewSlot.SlotStatus.COMPLETED.equals(s.getStatus())
                          && "PENDING".equals(s.getDecision()))
                .count();

        return EvaluatorDashboardDto.builder()
                // KPIs
                .todayInterviewsCount(todaySlots.size())
                .nextInterviewTime(nextInterviewTime)
                .assignedTestsCount(assignedTests.size())
                .activeTestsCount((int) assignedTests.stream()
                        .filter(t -> "ACTIVE".equals(t.getStatus())).count())
                .draftTestsCount((int) assignedTests.stream()
                        .filter(t -> "DRAFT".equals(t.getStatus())).count())
                .pendingReviewsCount(pendingReviews)
                .completedThisWeek(thisWeekCompleted.size())
                .acceptedThisWeek((int) thisWeekCompleted.stream()
                        .filter(s -> "ACCEPTED".equals(s.getDecision())).count())
                .rejectedThisWeek((int) thisWeekCompleted.stream()
                        .filter(s -> "REJECTED".equals(s.getDecision())).count())
                // Slots
                .todaySlots(todaySlots.stream().map(s -> toSlotDto(s, evaluatorId)).collect(Collectors.toList()))
                .upcomingSlots(upcomingSlots.stream().map(s -> toSlotDto(s, evaluatorId)).collect(Collectors.toList()))
                .recentCompleted(recentCompleted.stream().map(s -> toSlotDto(s, evaluatorId)).collect(Collectors.toList()))
                // Tests
                .assignedTests(assignedTests)
                // Pipeline
                .pipeline(pipeline)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns all InterviewSlots where assigneeId matches the evaluator.
     */
    private List<InterviewSlot> fetchEvaluatorSlots(String evaluatorId) {
        List<Interview> myInterviews = interviewRepository.findByAssigneeId(evaluatorId);

        List<InterviewSlot> slots = new ArrayList<>();
        for (Interview interview : myInterviews) {
            slots.addAll(slotRepository.findByInterviewId(interview.getId()));
        }
        return slots;
    }

    /**
     * Builds the list of technical tests that this evaluator is responsible for.
     * Uses unified Assessment model instead of legacy JobTest.
     */
    private List<AssignedTestDto> buildAssignedTests(String evaluatorId) {
        List<Job> jobs = jobRepository.findAll();
        List<AssignedTestDto> result = new ArrayList<>();

        for (Job job : jobs) {
            if (job.getWorkflowStages() == null) continue;

            for (WorkflowStage stage : job.getWorkflowStages()) {
                if (stage.getStageType() != StageType.TECHNICAL_TEST) continue;

                // Only stages assigned to this evaluator
                String stageAssignee = resolveAssigneeId(stage, job);
                if (!evaluatorId.equals(stageAssignee)) continue;

                // Find the Assessment linked to this stage (was JobTest)
                Optional<Assessment> assessmentOpt = assessmentRepository.findByWorkflowStageId(stage.getId());

                if (assessmentOpt.isPresent()) {
                    Assessment a = assessmentOpt.get();
                    // Use unified TestSession (filter TECHNICAL type if needed)
                    List<TestSession> sessions = testSessionRepository.findByAssessmentId(a.getId());
                    long completed = sessions.stream()
                            .filter(s -> s.getStatus() == TestSession.SessionStatus.COMPLETED)
                            .count();
                    int total  = sessions.size();
                    int rate   = total > 0 ? (int) Math.round((double) completed / total * 100) : 0;
                    int qCount = countAllQuestions(a);

                    result.add(AssignedTestDto.builder()
                            .testId(a.getId())
                            .testName(a.getName())
                            .jobId(job.getId())
                            .jobTitle(job.getTitle())
                            .department(job.getDepartment())
                            .status(a.getStatus().name())
                            .themesCount(a.getThemes() != null ? a.getThemes().size() : 0)
                            .questionsCount(qCount)
                            .candidatesCount(total)
                            .completedCount((int) completed)
                            .completionRate(rate)
                            .stageType(StageType.TECHNICAL_TEST.name())
                            .createdAt(a.getCreatedAt() != null ? a.getCreatedAt().toString() : null)
                            .build());
                } else {
                    // Stage not yet configured — show as DRAFT stub
                    result.add(AssignedTestDto.builder()
                            .testId(stage.getId())
                            .testName(stage.getName())
                            .jobId(job.getId())
                            .jobTitle(job.getTitle())
                            .department(job.getDepartment())
                            .status("DRAFT")
                            .themesCount(0)
                            .questionsCount(0)
                            .candidatesCount(0)
                            .completedCount(0)
                            .completionRate(0)
                            .stageType(StageType.TECHNICAL_TEST.name())
                            .createdAt(job.getCreatedAt() != null ? job.getCreatedAt().toString() : null)
                            .build());
                }
            }
        }
        return result;
    }

    private PipelineDto buildPipeline(List<InterviewSlot> mySlots, List<AssignedTestDto> tests) {
        int awaitingInterview = (int) mySlots.stream()
                .filter(s -> InterviewSlot.SlotStatus.SCHEDULED.equals(s.getStatus()))
                .count();

        int testInProgress = 0;
        int pendingReview  = 0;
        for (AssignedTestDto t : tests) {
            if ("ACTIVE".equals(t.getStatus())) {
                int notDone = t.getCandidatesCount() - t.getCompletedCount();
                testInProgress += Math.max(notDone, 0);
                pendingReview  += t.getCompletedCount();
            }
        }

        int accepted = (int) mySlots.stream()
                .filter(s -> "ACCEPTED".equals(s.getDecision())).count();
        int rejected = (int) mySlots.stream()
                .filter(s -> "REJECTED".equals(s.getDecision())).count();

        return PipelineDto.builder()
                .awaitingInterview(awaitingInterview)
                .testInProgress(testInProgress)
                .pendingReview(pendingReview)
                .accepted(accepted)
                .rejected(rejected)
                .build();
    }

    // ── Mapper: InterviewSlot → SlotDto ──────────────────────────────────────

    private SlotDto toSlotDto(InterviewSlot s, String evaluatorId) {
        Interview interview = null;
        try {
            interview = interviewRepository.findByInterviewId(s.getInterviewId()).orElse(null);
        } catch (Exception ignored) {}

        String jobTitle  = interview != null ? interview.getJobTitle()  : "";
        String stageName = interview != null ? interview.getStageName()  : "";
        String stageType = interview != null && interview.getStageType() != null
                ? interview.getStageType().name() : "";
        int    duration  = interview != null && interview.getDurationMinutes() != null
                ? interview.getDurationMinutes() : 60;

        return SlotDto.builder()
                .slotId(s.getId())
                .candidateId(s.getCandidateId())
                .candidateName(s.getCandidateName())
                .candidateEmail(s.getCandidateEmail())
                .jobTitle(jobTitle)
                .stageName(stageName)
                .stageType(stageType)
                .slotStart(s.getSlotStart()  != null ? s.getSlotStart().toString()  : null)
                .slotEnd(s.getSlotEnd()      != null ? s.getSlotEnd().toString()    : null)
                .durationMinutes(duration)
                .status(s.getStatus()        != null ? s.getStatus().name()         : "SCHEDULED")
                .decision(s.getDecision()    != null ? s.getDecision()              : "PENDING")
                .overallScore(s.getOverallScore())
                .build();
    }

    // ── resolveAssigneeId (mirrors AssessmentService) ─────────────────────────

    private String resolveAssigneeId(WorkflowStage stage, Job job) {
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

    /**
     * Counts all questions for an Assessment.
     * Uses unified QuestionRepository instead of legacy SimpleQuestionRepository.
     */
    private int countAllQuestions(Assessment a) {
        if (a.getThemes() == null) return 0;
        // Psychometric questions via theme models
        int psycho = a.getThemes().stream()
                .flatMap(th -> th.getThemeModels().stream())
                .mapToInt(tm -> tm.getQuestions().size()).sum();
        // Technical questions via theme (unified Question with kind PROBLEM_SOLVING / QCM)
        int technical = a.getThemes().stream()
                .mapToInt(th -> questionRepository.countByThemeId(th.getId())).sum();
        return psycho + technical;
    }
}
