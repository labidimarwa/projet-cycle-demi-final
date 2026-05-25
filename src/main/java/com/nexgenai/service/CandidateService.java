package com.nexgenai.service;

import com.nexgenai.dto.candidate.CandidateApplicationResponse;
import com.nexgenai.dto.candidate.CandidateProfileResponse;
import com.nexgenai.dto.candidate.StageProgressDTO;
import com.nexgenai.dto.candidate.UpdateCandidateProfileRequest;
import com.nexgenai.model.*;
import com.nexgenai.model.enums.AssessmentType;
import com.nexgenai.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CandidateService {

    private final UserRepository                      userRepository;
    private final CandidateRepository                 candidateRepository;
    private final JobRepository                       jobRepository;
    private final JobMatchRepository                  jobMatchRepository;
    private final ApplicationRepository               applicationRepository;
    private final ApplicationStageProgressRepository  stageProgressRepository;
    private final FileStorageService                  fileStorageService;
    private final ChatSessionRepository               chatSessionRepository;
    private final AssessmentRepository                assessmentRepository;
    private final TestSessionRepository               testSessionRepository;
    private final QuestionRepository                  questionRepository;
    // Injected to avoid circular dependency: CandidateService ↔ ApplicationStageProgressService
    private final ApplicationStageProgressService     stageProgressService;

    // ── Profile ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CandidateProfileResponse getProfile(String email) {
        Candidate c = findCandidate(email);
        return CandidateProfileResponse.builder()
            .id(c.getId())
            .firstName(c.getFirstName())
            .lastName(c.getLastName())
            .email(c.getEmail())
            .cvPath(c.getCvPath())
            .build();
    }

    @Transactional
    public CandidateProfileResponse updateProfile(String email, UpdateCandidateProfileRequest req) {
        Candidate c = findCandidate(email);
        if (req.getFirstName() != null) c.setFirstName(req.getFirstName());
        if (req.getLastName()  != null) c.setLastName(req.getLastName());
        userRepository.save(c);
        return getProfile(email);
    }

    // ── CV ────────────────────────────────────────────────────────────────────

    @Transactional
    public String uploadCv(String email, MultipartFile file) {
        Candidate c = findCandidate(email);
        String cvPath = fileStorageService.saveFile(email, file);
        c.setCvPath(cvPath);
        userRepository.save(c);
        return cvPath;
    }

    public Path getCvFilePath(String email) {
        Candidate c = findCandidate(email);
        if (c.getCvPath() == null) throw new RuntimeException("No CV uploaded");
        return fileStorageService.getFilePath(c.getCvPath());
    }

    public String getCvDisplayName(String email) {
        Candidate c = findCandidate(email);
        return fileStorageService.getDisplayName(c.getCvPath());
    }

    // ── Match scores ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Integer> getMatchScores(String email) {
        Candidate c = findCandidate(email);
        return jobMatchRepository.findByCandidateId(c.getId()).stream()
            .filter(m -> m.getScore() != null)
            .collect(Collectors.toMap(JobMatch::getJobId, JobMatch::getScore));
    }

    @Transactional(readOnly = true)
    public Optional<JobMatch> getMatchDetail(String email, String jobId) {
        Candidate c = findCandidate(email);
        return jobMatchRepository.findByCandidateIdAndJobId(c.getId(), jobId);
    }

    // ── Applications — main method ────────────────────────────────────────────

    /**
     * Returns all applications for the candidate with:
     * - job info
     * - application status
     * - AI match score
     * - chat score
     * - test sessions
     * - stageProgress[] — the HR-defined workflow stages with live status
     */
    @Transactional(readOnly = true)
    public List<CandidateApplicationResponse> getApplications(String email) {
        Candidate c = findCandidate(email);
        return getApplicationsForCandidate(c.getId());
    }

    // ── Applications: read-side logic (merged from CandidateApplicationService) ─

    @Transactional(readOnly = true)
    public List<CandidateApplicationResponse> getApplicationsForCandidate(String candidateId) {
        List<Application> applications = applicationRepository.findByCandidateId(candidateId);
        return applications.stream()
                .map(app -> buildApplicationResponse(candidateId, app))
                .collect(Collectors.toList());
    }

    private CandidateApplicationResponse buildApplicationResponse(String candidateId, Application app) {
        String jobId = app.getJobId();
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

        Optional<JobMatch> matchOpt = jobMatchRepository.findByCandidateIdAndJobId(candidateId, jobId);
        Integer matchScore    = matchOpt.map(JobMatch::getScore).orElse(null);
        boolean matchComputed = matchOpt.isPresent();

        Optional<ChatSession> chatOpt = chatSessionRepository.findByCandidateIdAndJobId(candidateId, jobId);
        boolean chatDone  = chatOpt.map(ChatSession::isDone).orElse(false);
        Integer chatScore = chatOpt.map(ChatSession::getInterviewScore).orElse(null);

        List<Assessment> assessments = assessmentRepository.findByJobId(jobId);
        List<CandidateApplicationResponse.JobTestInfo> testInfos = assessments.stream()
                .map(a -> buildTestInfo(candidateId, a))
                .collect(Collectors.toList());

        List<StageProgressDTO> stages = loadStageProgress(candidateId, jobId);

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

    private CandidateApplicationResponse.JobTestInfo buildTestInfo(String candidateId, Assessment assessment) {
        String testCategory;
        if (assessment.getType() == AssessmentType.TECHNICAL) {
            testCategory = "TECHNICAL";
        } else {
            boolean hasTechnicalQuestions = assessment.getThemes().stream()
                    .anyMatch(theme -> questionRepository.countByThemeId(theme.getId()) > 0);
            testCategory = hasTechnicalQuestions ? "TECHNICAL" : "PSYCHOMETRIC";
        }

        Optional<TestSession> sessionOpt =
                testSessionRepository.findByCandidateIdAndAssessmentId(candidateId, assessment.getId());

        int questionsCount;
        if ("TECHNICAL".equals(testCategory)) {
            int techCount = assessment.getThemes().stream()
                    .mapToInt(th -> questionRepository.countByThemeId(th.getId()))
                    .sum();
            int psychoCount = assessment.getThemes().stream()
                    .flatMap(th -> th.getThemeModels().stream())
                    .mapToInt(tm -> tm.getQuestions().size())
                    .sum();
            questionsCount = techCount + psychoCount;
        } else {
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
                .sessionStatus(sessionOpt.map(s -> s.getStatus().name()).orElse("PENDING"))
                .score(sessionOpt.map(TestSession::getScore).orElse(null))
                .startedAt(sessionOpt.map(TestSession::getStartedAt).orElse(null))
                .completedAt(sessionOpt.map(TestSession::getCompletedAt).orElse(null))
                .testCategory(testCategory)
                .build();
    }

    private List<StageProgressDTO> loadStageProgress(String candidateId, String jobId) {
        try {
            List<ApplicationStageProgress> rows = stageProgressService.getProgress(candidateId, jobId);
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Candidate findCandidate(String email) {
        return candidateRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("Candidate not found: " + email));
    }
}