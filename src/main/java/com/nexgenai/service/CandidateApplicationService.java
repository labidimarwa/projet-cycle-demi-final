// src/main/java/com/nexgenai/service/CandidateApplicationService.java

package com.nexgenai.service;

import com.nexgenai.dto.candidate.CandidateApplicationResponse;
import com.nexgenai.dto.candidate.StageProgressDTO;
import com.nexgenai.model.*;
import com.nexgenai.model.enums.AssessmentType;
import com.nexgenai.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 2 refactoring: updated to use unified Assessment, TestSession, and
 * Question models + repositories instead of the legacy JobTest,
 * TechnicalSession, and SimpleQuestion types.
 */
@Service
@RequiredArgsConstructor
public class CandidateApplicationService {

    private final ApplicationRepository              applicationRepository;
    private final JobMatchRepository                 jobMatchRepository;
    private final JobRepository                      jobRepository;
    private final ChatSessionRepository              chatSessionRepository;
    private final AssessmentRepository               assessmentRepository;    // was JobTestRepository
    private final TestSessionRepository              testSessionRepository;   // unified (was TestSessionRepository + TechnicalSessionRepository)
    private final QuestionRepository                 questionRepository;      // was SimpleQuestionRepository
    private final ApplicationStageProgressService    stageProgressService;

    // ── Point d'entrée principal ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CandidateApplicationResponse> getApplicationsForCandidate(String candidateId) {
        List<Application> applications = applicationRepository.findByCandidateId(candidateId);
        return applications.stream()
                .map(app -> buildResponse(candidateId, app))
                .collect(Collectors.toList());
    }

    // ── Construction de la réponse par application ────────────────────────────

    private CandidateApplicationResponse buildResponse(String candidateId, Application app) {

        String jobId = app.getJobId();
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

        // ── Score CV via JobMatch ─────────────────────────────────────────────
        Optional<JobMatch> matchOpt = jobMatchRepository
                .findByCandidateIdAndJobId(candidateId, jobId);
        Integer matchScore    = matchOpt.map(JobMatch::getScore).orElse(null);
        boolean matchComputed = matchOpt.isPresent();

        // ── Score / statut chat ───────────────────────────────────────────────
        Optional<ChatSession> chatOpt = chatSessionRepository
                .findByCandidateIdAndJobId(candidateId, jobId);
        boolean chatDone  = chatOpt.map(ChatSession::isDone).orElse(false);
        Integer chatScore = chatOpt.map(ChatSession::getInterviewScore).orElse(null);

        // ── Assessments du job (was JobTests) ─────────────────────────────────
        List<Assessment> assessments = assessmentRepository.findByJobId(jobId);
        List<CandidateApplicationResponse.JobTestInfo> testInfos = assessments.stream()
                .map(a -> buildTestInfo(candidateId, a))
                .collect(Collectors.toList());

        // ── Stages de progression HR ──────────────────────────────────────────
        List<StageProgressDTO> stages = loadStageProgress(candidateId, jobId);

        // ── Statut candidature ────────────────────────────────────────────────
        String status = "PENDING";
        try {
            if (app.getStatus() != null) status = app.getStatus().name();
        } catch (Exception ignored) {}

        return CandidateApplicationResponse.builder()
                .jobId(jobId)
                .jobTitle(job.getTitle())
                .department(job.getDepartment())
                .location(job.getLocation())
                .contractType(job.getContractType() != null ? job.getContractType().name() : null)
                .experienceLevel(job.getExperienceLevel() != null ? job.getExperienceLevel().name() : null)
                .applicationStatus(status)
                .appliedAt(app.getAppliedAt())
                .matchScore(matchScore)
                .matchComputed(matchComputed)
                .chatDone(chatDone)
                .chatScore(chatScore)
                .tests(testInfos)
                .stageProgress(stages)
                .build();
    }

    // ── Construit le JobTestInfo avec testCategory ────────────────────────────

    private CandidateApplicationResponse.JobTestInfo buildTestInfo(String candidateId, Assessment assessment) {

        // Determine category based on assessment type
        String testCategory;
        if (assessment.getType() == AssessmentType.TECHNICAL) {
            testCategory = "TECHNICAL";
        } else {
            // Check if there are technical questions (Question with themeId set)
            boolean hasTechnicalQuestions = assessment.getThemes().stream()
                    .anyMatch(theme -> questionRepository.countByThemeId(theme.getId()) > 0);
            testCategory = hasTechnicalQuestions ? "TECHNICAL" : "PSYCHOMETRIC";
        }

        // Look up the unified TestSession for this candidate + assessment
        Optional<TestSession> sessionOpt =
                testSessionRepository.findByCandidateIdAndAssessmentId(candidateId, assessment.getId());

        // Count questions
        int questionsCount;
        if ("TECHNICAL".equals(testCategory)) {
            // Technical: count questions attached to themes
            int techCount = assessment.getThemes().stream()
                    .mapToInt(th -> questionRepository.countByThemeId(th.getId()))
                    .sum();
            // May also have psychometric questions via theme models
            int psychoCount = assessment.getThemes().stream()
                    .flatMap(th -> th.getThemeModels().stream())
                    .mapToInt(tm -> tm.getQuestions().size())
                    .sum();
            questionsCount = techCount + psychoCount;
        } else {
            // Psychometric: count via theme models
            questionsCount = assessment.getThemes().stream()
                    .flatMap(th -> th.getThemeModels().stream())
                    .mapToInt(tm -> tm.getQuestions().size())
                    .sum();
        }

        return CandidateApplicationResponse.JobTestInfo.builder()
                .testId(assessment.getId())
                .testName(assessment.getName())
                .testDescription(assessment.getDescription())
                .testStatus(assessment.getStatus().name())
                .questionsCount(questionsCount)
                .sessionStatus(sessionOpt
                        .map(s -> s.getStatus().name())
                        .orElse("PENDING"))
                .score(sessionOpt
                        .map(TestSession::getScore)
                        .orElse(null))
                .startedAt(sessionOpt
                        .map(TestSession::getStartedAt)
                        .orElse(null))
                .completedAt(sessionOpt
                        .map(TestSession::getCompletedAt)
                        .orElse(null))
                .testCategory(testCategory)
                .build();
    }

    // ── Charge les stages HR depuis ApplicationStageProgressService ───────────

    private List<StageProgressDTO> loadStageProgress(String candidateId, String jobId) {
        try {
            List<ApplicationStageProgress> rows =
                    stageProgressService.getProgress(candidateId, jobId);
            return rows.stream()
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
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
