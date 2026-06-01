package com.nexgenai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexgenai.dto.candidate.StageProgressDTO;
import com.nexgenai.dto.hr.*;
import com.nexgenai.model.*;
import com.nexgenai.model.Application.ApplicationStatus;
import com.nexgenai.model.enums.NotificationType;
import com.nexgenai.model.enums.StageProgressStatus;
import com.nexgenai.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Unified HR Service — Phase 2 refactoring.
 *
 * Merges the previous {@code HrService} (applicant list/detail, CV, status),
 * {@code HrCandidatesService} (job candidates with match/chat detail), and
 * {@code HrDecisionService} (accept/reject decisions with email) into a
 * single, cohesive service.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HrService {

    private final ApplicationRepository               applicationRepository;
    private final ApplicationStageProgressRepository  stageProgressRepository;
    private final ApplicationStageProgressService     stageProgressService;
    private final UserRepository                      userRepository;
    private final JobRepository                       jobRepository;
    private final JobMatchRepository                  jobMatchRepository;
    private final EmailService                        emailService;
    private final NotificationService                 notificationService;
    private final ObjectMapper                        objectMapper;

    @Value("${app.cv.upload-dir:uploads/cv}")
    private String uploadDir;

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final String STATUS_PENDING  = "PENDING";
    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String CANDIDATE_APPLICATIONS_PATH = "/candidate/applications";

    // ═══════════════════════════════════════════════════════════════════════════
    // APPLICANTS — List & Detail (from old HrService)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the summary list of all candidates who applied to a given job.
     * Ordered by AI match score DESC (best first).
     */
    @Transactional(readOnly = true)
    public List<ApplicantSummaryResponse> getApplicants(String jobId) {
        return applicationRepository.findByJobId(jobId).stream()
                .map(app -> buildApplicantSummary(app, jobId))
                .sorted(Comparator.comparingInt(
                        (ApplicantSummaryResponse r) -> r.getMatchScore() != null
                                ? r.getMatchScore() : -1).reversed())
                .toList();
    }

    /**
     * Returns full detail for one candidate on a job:
     * profile, CV match, chat transcript, and process stages.
     */
    @Transactional
    public ApplicantDetailResponse getApplicantDetail(String jobId, String candidateId) {
        Candidate candidate = findCandidate(candidateId);

        Application application = applicationRepository
                .findByCandidateIdAndJobId(candidateId, jobId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Application not found: candidate=" + candidateId + " job=" + jobId));

        // AI match
        Optional<JobMatch> matchOpt = jobMatchRepository.findByCandidateIdAndJobId(candidateId, jobId);
        Integer matchScore    = matchOpt.map(JobMatch::getScore).orElse(null);
        boolean matchComputed = matchScore != null;

        List<ApplicantDetailResponse.DimensionScore> dimensions = matchOpt
                .map(this::buildDimensions).orElse(List.of());

        List<String> skillsMatched = matchOpt
                .map(m -> parseCommaSeparated(m.getSkillsMatched())).orElse(List.of());
        List<String> skillsMissing = matchOpt
                .map(m -> parseCommaSeparated(m.getSkillsMissing())).orElse(List.of());

        // Stage progress
        List<ApplicationStageProgress> stageRows = stageProgressService.getProgress(candidateId, jobId);
        List<StageProgressDTO> stageProgress = stageRows.stream()
                .map(this::mapStageProgress).toList();

        return ApplicantDetailResponse.builder()
                .candidateId(candidateId)
                .firstName(candidate.getFirstName())
                .lastName(candidate.getLastName())
                .email(candidate.getEmail())
                .linkedinUrl(candidate.getLinkedinUrl())
                .githubUrl(candidate.getGithubUrl())
                .portfolioUrl(candidate.getPortfolioUrl())
                .hasCv(candidate.getCvPath() != null)
                .cvPath(candidate.getCvPath())
                .applicationStatus(application.getStatus() != null
                        ? application.getStatus().name() : STATUS_PENDING)
                .appliedAt(application.getAppliedAt() != null
                        ? application.getAppliedAt().format(ISO_FMT) : null)
                .matchScore(matchScore)
                .matchComputed(matchComputed)
                .dimensions(dimensions)
                .skillsMatched(skillsMatched)
                .skillsMissing(skillsMissing)
                .stageProgress(stageProgress)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UPDATE APPLICATION STATUS (from old HrService)
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public void updateApplicantStatus(String jobId, String candidateId, String status) {
        Application application = applicationRepository
                .findByCandidateIdAndJobId(candidateId, jobId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Application not found: candidate=" + candidateId + " job=" + jobId));
        try {
            application.setStatus(ApplicationStatus.valueOf(status.toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status: " + status);
        }
        applicationRepository.save(application);
        log.info("✅ Application status updated: candidate={} job={} → {}", candidateId, jobId, status);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CV DOWNLOAD (from old HrService)
    // ═══════════════════════════════════════════════════════════════════════════

    public Path getCvPath(String candidateId) {
        Candidate candidate = findCandidate(candidateId);
        if (candidate.getCvPath() == null)
            throw new IllegalStateException("No CV for candidate: " + candidateId);
        String[] parts    = candidate.getCvPath().split("\\|");
        String   fileName = parts.length > 1 ? parts[1] : parts[0];
        return Paths.get(uploadDir).toAbsolutePath().resolve(fileName);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JOB CANDIDATES — Summary & Detail (from old HrCandidatesService)
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public JobCandidatesResponse getCandidatesForJob(String jobId) {
        Job job = jobRepository.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        List<Application> applications = applicationRepository.findByJobId(jobId);

        List<JobCandidatesResponse.CandidateSummaryDTO> summaries = applications.stream()
            .map(app -> buildCandidateSummary(app, jobId))
            .sorted(Comparator.comparingInt(
                (JobCandidatesResponse.CandidateSummaryDTO s) ->
                    s.getMatchScore() == null ? 0 : s.getMatchScore()
            ).reversed())
            .toList();

        double avg = summaries.stream()
            .filter(s -> s.getMatchScore() != null && s.getMatchScore() > 0)
            .mapToInt(JobCandidatesResponse.CandidateSummaryDTO::getMatchScore)
            .average().orElse(0.0);

        return JobCandidatesResponse.builder()
            .jobId(jobId)
            .jobTitle(job.getTitle())
            .totalApplicants(summaries.size())
            .avgMatchScore(Math.round(avg * 10.0) / 10.0)
            .candidates(summaries)
            .build();
    }

    @Transactional(readOnly = true)
    public CandidateApplicationDetailDTO getCandidateDetail(String jobId, String candidateId) {
        Candidate candidate = findCandidate(candidateId);
        Application app = applicationRepository
            .findByCandidateIdAndJobId(candidateId, jobId)
            .orElseThrow(() -> new IllegalArgumentException("Application not found"));

        // Match
        CandidateApplicationDetailDTO.MatchDetailDTO matchDTO = null;
        Optional<JobMatch> matchOpt = jobMatchRepository.findByCandidateIdAndJobId(candidateId, jobId);
        if (matchOpt.isPresent()) {
            JobMatch m = matchOpt.get();
            matchDTO = CandidateApplicationDetailDTO.MatchDetailDTO.builder()
                .score(m.getScore())
                .verdict(m.getVerdict())
                .resume(m.getResume())
                .skillsMatched(m.getSkillsMatched())
                .skillsMissing(m.getSkillsMissing())
                .dimensionsJson(m.getDimensionsJson())
                .computedAt(m.getComputedAt())
                .build();
        }

        String cvDisplay = null;
        if (candidate.getCvPath() != null) {
            cvDisplay = candidate.getCvPath().contains("|")
                ? candidate.getCvPath().split("\\|")[0]
                : candidate.getCvPath();
        }

        return CandidateApplicationDetailDTO.builder()
            .candidateId(candidate.getId())
            .firstName(candidate.getFirstName())
            .lastName(candidate.getLastName())
            .email(candidate.getEmail())
            .city(candidate.getCity())
            .currentPosition(candidate.getCurrentPosition())
            .yearsOfExperience(candidate.getYearsOfExperience())
            .educationLevel(candidate.getEducationLevel())
            .university(candidate.getUniversity())
            .summary(candidate.getSummary())
            .cvDisplayName(cvDisplay)
            .linkedinUrl(candidate.getLinkedinUrl())
            .githubUrl(candidate.getGithubUrl())
            .portfolioUrl(candidate.getPortfolioUrl())
            .remoteWorkPreference(candidate.getRemoteWorkPreference())
            .certifications(candidate.getCertifications())
            .match(matchDTO)
            .appliedAt(app.getAppliedAt())
            .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DECISION — Accept / Reject (from old HrDecisionService)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * HR accepts or rejects a candidate after AI Match stage.
     * - ACCEPTED → marks current IN_PROGRESS stage COMPLETED, advances to next stage
     * - REJECTED → marks stage REJECTED, updates application status, sends rejection email
     */
    @Transactional
    public ApplicationDecisionResponse decide(String jobId, String candidateId,
                                               ApplicationDecisionRequest req) {
        String decision = req.getDecision().toUpperCase();
        if (!decision.equals(STATUS_ACCEPTED) && !decision.equals("REJECTED")) {
            throw new IllegalArgumentException("Decision must be ACCEPTED or REJECTED");
        }

        Candidate candidate = findCandidate(candidateId);

        Job job = jobRepository.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        JobMatch match = jobMatchRepository.findByCandidateIdAndJobId(candidateId, jobId)
            .orElseThrow(() -> new IllegalStateException("AI Match not found"));

        // Find the current IN_PROGRESS stage
        ApplicationStageProgress currentStage = stageProgressRepository
            .findByCandidateIdAndJobId(candidateId, jobId).stream()
            .filter(s -> s.getStatus() == StageProgressStatus.IN_PROGRESS)
            .min(Comparator.comparing(ApplicationStageProgress::getStageOrder))
            .orElseThrow(() -> new IllegalStateException("No active stage found"));

        String nextStageName = null;

        if (decision.equals(STATUS_ACCEPTED)) {
            currentStage.setStatus(StageProgressStatus.COMPLETED);
            currentStage.setCompletedAt(LocalDateTime.now());
            currentStage.setHrNote(req.getNote());
            stageProgressRepository.save(currentStage);

            // Activate next stage
            List<ApplicationStageProgress> allStages = stageProgressRepository
                .findByCandidateIdAndJobId(candidateId, jobId).stream()
                .sorted(Comparator.comparing(ApplicationStageProgress::getStageOrder))
                .toList();

            ApplicationStageProgress nextStage = allStages.stream()
                .filter(s -> s.getStageOrder() > currentStage.getStageOrder())
                .findFirst().orElse(null);

            if (nextStage != null) {
                nextStage.setStatus(StageProgressStatus.IN_PROGRESS);
                nextStage.setStartedAt(LocalDateTime.now());
                stageProgressRepository.save(nextStage);
                nextStageName = nextStage.getStageName();
            }

            sendAcceptanceEmail(candidate, job, match.getScore(), nextStageName, req.getNote());

            // Real-time: notify candidate they were accepted
            notificationService.send(
                candidateId, NotificationType.APPLICATION_ACCEPTED,
                "Application Accepted!",
                "Congratulations! Your application for \"" + job.getTitle() + "\" has been accepted.",
                jobId, "JOB", CANDIDATE_APPLICATIONS_PATH
            );

            // If next stage is a test, send a dedicated test-assigned notification
            if (nextStage != null) {
                String nextType = nextStage.getStageType();
                if ("TECHNICAL_TEST".equals(nextType) || "RH_TEST".equals(nextType)) {
                    notificationService.send(
                        candidateId, NotificationType.TEST_ASSIGNED,
                        "New Test Available",
                        "You have a new test to complete: \"" + nextStage.getStageName() + "\".",
                        jobId, "JOB", CANDIDATE_APPLICATIONS_PATH
                    );
                } else if ("RH_INTERVIEW".equals(nextType) || "TECHNICAL_INTERVIEW".equals(nextType)
                        || "ADMIN_INTERVIEW".equals(nextType)) {
                    notificationService.send(
                        candidateId, NotificationType.INTERVIEW_SCHEDULED,
                        "Interview Stage Activated",
                        "Your next step is an interview: \"" + nextStage.getStageName() + "\".",
                        jobId, "JOB", CANDIDATE_APPLICATIONS_PATH
                    );
                } else {
                    notificationService.send(
                        candidateId, NotificationType.STAGE_COMPLETED,
                        "Stage Advanced",
                        "You have been moved to the next stage: \"" + nextStage.getStageName() + "\".",
                        jobId, "JOB", CANDIDATE_APPLICATIONS_PATH
                    );
                }
            }

        } else {
            currentStage.setStatus(StageProgressStatus.REJECTED);
            currentStage.setCompletedAt(LocalDateTime.now());
            currentStage.setHrNote(req.getNote());
            stageProgressRepository.save(currentStage);

            applicationRepository.findByCandidateIdAndJobId(candidateId, jobId)
                .ifPresent(app -> {
                    app.setStatus(ApplicationStatus.REJECTED);
                    applicationRepository.save(app);
                });

            sendRejectionEmail(candidate, job, req.getNote());

            // Real-time: notify candidate they were rejected
            notificationService.send(
                candidateId, NotificationType.APPLICATION_REJECTED,
                "Application Update",
                "Thank you for applying to \"" + job.getTitle() + "\". We have decided to move forward with other candidates.",
                jobId, "JOB", CANDIDATE_APPLICATIONS_PATH
            );
        }

        return ApplicationDecisionResponse.builder()
            .candidateId(candidateId)
            .jobId(jobId)
            .decision(decision)
            .nextStageName(nextStageName)
            .message(buildDecisionMessage(decision, nextStageName))
            .build();
    }

    private String buildDecisionMessage(String decision, String nextStageName) {
        if (decision.equals(STATUS_ACCEPTED)) {
            String stage = nextStageName != null ? nextStageName : "final stage";
            return "Candidate accepted. Moved to: " + stage;
        }
        return "Candidate rejected. Notification email sent.";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE — Applicant Summary Builder (from old HrService)
    // ═══════════════════════════════════════════════════════════════════════════

    private ApplicantSummaryResponse buildApplicantSummary(Application app, String jobId) {
        String candidateId = app.getCandidateId();

        Candidate candidate = userRepository.findById(candidateId)
                .filter(u -> u instanceof Candidate)
                .map(u -> (Candidate) u)
                .orElse(null);

        if (candidate == null) {
            return ApplicantSummaryResponse.builder()
                    .candidateId(candidateId)
                    .applicationStatus(app.getStatus() != null ? app.getStatus().name() : STATUS_PENDING)
                    .build();
        }

        Optional<JobMatch> matchOpt = jobMatchRepository.findByCandidateIdAndJobId(candidateId, jobId);
        Integer matchScore    = matchOpt.map(JobMatch::getScore).orElse(null);
        boolean matchComputed = matchScore != null;

        String  currentStageName = null;
        int     stageProgressPct = 0;
        List<ApplicationStageProgress> stageRows =
                stageProgressRepository.findByCandidateAndJob(candidateId, jobId);
        if (!stageRows.isEmpty()) {
            long total    = stageRows.size();
            long completed = stageRows.stream()
                    .filter(r -> r.getStatus().name().equals("COMPLETED")).count();
            stageProgressPct = (int) Math.round((double) completed / total * 100);
            currentStageName = stageRows.stream()
                    .filter(r -> r.getStatus().name().equals("IN_PROGRESS"))
                    .findFirst()
                    .map(ApplicationStageProgress::getStageName)
                    .orElse(null);
        }

        return ApplicantSummaryResponse.builder()
                .candidateId(candidateId)
                .firstName(candidate.getFirstName())
                .lastName(candidate.getLastName())
                .email(candidate.getEmail())
                .applicationStatus(app.getStatus() != null ? app.getStatus().name() : STATUS_PENDING)
                .appliedAt(app.getAppliedAt() != null ? app.getAppliedAt().format(ISO_FMT) : null)
                .matchScore(matchScore)
                .matchComputed(matchComputed)
                .currentStageName(currentStageName)
                .stageProgress(stageProgressPct)
                .hasCv(candidate.getCvPath() != null)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE — Candidate Summary Builder (from old HrCandidatesService)
    // ═══════════════════════════════════════════════════════════════════════════

    private JobCandidatesResponse.CandidateSummaryDTO buildCandidateSummary(Application app, String jobId) {
        Candidate c = findCandidate(app.getCandidateId());

        Integer matchScore   = null;
        String  matchVerdict = null;
        Optional<JobMatch> m = jobMatchRepository.findByCandidateIdAndJobId(app.getCandidateId(), jobId);
        if (m.isPresent()) { matchScore = m.get().getScore(); matchVerdict = m.get().getVerdict(); }

        String cvDisplay = null;
        if (c.getCvPath() != null) {
            cvDisplay = c.getCvPath().contains("|")
                ? c.getCvPath().split("\\|")[0] : c.getCvPath();
        }

        return JobCandidatesResponse.CandidateSummaryDTO.builder()
            .candidateId(c.getId())
            .firstName(c.getFirstName())
            .lastName(c.getLastName())
            .email(c.getEmail())
            .currentPosition(c.getCurrentPosition())
            .yearsOfExperience(c.getYearsOfExperience())
            .city(c.getCity())
            .matchScore(matchScore)
            .matchVerdict(matchVerdict)
            .hasCv(c.getCvPath() != null)
            .cvDisplayName(cvDisplay)
            .appliedAt(app.getAppliedAt())
            .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE — Shared Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private Candidate findCandidate(String candidateId) {
        return userRepository.findById(candidateId)
                .filter(u -> u instanceof Candidate)
                .map(u -> (Candidate) u)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));
    }

    private List<ApplicantDetailResponse.DimensionScore> buildDimensions(JobMatch m) {
        // Placeholder — adapt to actual JobMatch dimension fields
        return List.of();
    }

    private StageProgressDTO mapStageProgress(ApplicationStageProgress p) {
        return new StageProgressDTO(
                p.getId(), p.getStageOrder(), p.getStageName(), p.getStageType(),
                p.getStatus().name(),
                p.getStartedAt()   != null ? p.getStartedAt().toString()   : null,
                p.getCompletedAt() != null ? p.getCompletedAt().toString() : null,
                p.getHrNote()
        );
    }

    private List<String> parseCommaSeparated(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE — Email Templates (from old HrDecisionService)
    // ═══════════════════════════════════════════════════════════════════════════

    private void sendAcceptanceEmail(Candidate candidate, Job job,
                                     Integer matchScore, String nextStage, String hrNote) {
        String subject = "🎉 Congratulations! Your application for \"" + job.getTitle() + "\" has been selected";
        String html = buildAcceptanceHtml(candidate, job, matchScore, nextStage, hrNote);
        try {
            emailService.sendEmail(candidate.getEmail(), subject, html);
        } catch (Exception e) {
            log.error("Failed to send acceptance email to {}", candidate.getEmail(), e);
        }
    }

    private void sendRejectionEmail(Candidate candidate, Job job, String hrNote) {
        String subject = "Your application for \"" + job.getTitle() + "\" — Update";
        String html = buildRejectionHtml(candidate, job, hrNote);
        try {
            emailService.sendEmail(candidate.getEmail(), subject, html);
        } catch (Exception e) {
            log.error("Failed to send rejection email to {}", candidate.getEmail(), e);
        }
    }

    private String buildAcceptanceHtml(Candidate c, Job job,
                                        Integer score, String nextStage, String hrNote) {
        String next = nextStage != null ? nextStage : "the next evaluation phase";
        String note = (hrNote != null && !hrNote.isBlank())
            ? "<p style='margin:16px 0 0;padding:14px 16px;background:#f0fdf4;"
              + "border-left:3px solid #22c55e;border-radius:6px;color:#166534;"
              + "font-size:14px;'><strong>Note from HR:</strong> " + hrNote + "</p>"
            : "";

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;padding:0;background:#f8fafc;font-family:'Segoe UI',Arial,sans-serif;">
              <table width="100%%" cellpadding="0" cellspacing="0" style="background:#f8fafc;padding:40px 0;">
                <tr><td align="center">
                  <table width="560" cellpadding="0" cellspacing="0"
                         style="background:#ffffff;border-radius:16px;overflow:hidden;
                                box-shadow:0 4px 24px rgba(0,0,0,0.08);">
                    <tr>
                      <td style="background:linear-gradient(135deg,#6366f1 0%%,#4f46e5 100%%);
                                 padding:36px 40px;text-align:center;">
                        <div style="font-size:48px;margin-bottom:12px;">🎉</div>
                        <h1 style="margin:0;color:#ffffff;font-size:24px;font-weight:700;">
                          Congratulations, %s!</h1>
                        <p style="margin:8px 0 0;color:#c7d2fe;font-size:15px;">
                          Great news about your application</p>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:36px 40px;">
                        <p style="margin:0 0 20px;color:#374151;font-size:15px;line-height:1.6;">
                          We're thrilled to let you know that after reviewing your profile and
                          AI matching analysis, you have been <strong style="color:#4f46e5;">
                          selected to advance</strong> in the recruitment process for:</p>
                        <div style="background:#f5f3ff;border-radius:12px;padding:20px 24px;
                                    margin:0 0 24px;border:1px solid #e0e7ff;">
                          <p style="margin:0;font-size:18px;font-weight:700;color:#1e1b4b;">%s</p>
                          <p style="margin:6px 0 0;font-size:13px;color:#6b7280;">%s %s</p>
                        </div>
                        %s
                        <div style="background:#eff6ff;border-radius:12px;padding:20px 24px;
                                    margin:24px 0 0;border:1px solid #bfdbfe;">
                          <p style="margin:0;font-size:13px;font-weight:600;color:#1d4ed8;
                                    text-transform:uppercase;letter-spacing:0.5px;">Next Step</p>
                          <p style="margin:8px 0 0;font-size:16px;font-weight:700;color:#1e3a8a;">%s</p>
                          <p style="margin:8px 0 0;font-size:13px;color:#3b82f6;">
                            You will be contacted shortly with further details.</p>
                        </div>
                        %s
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:20px 40px 36px;text-align:center;border-top:1px solid #f1f5f9;">
                        <p style="margin:0;font-size:12px;color:#9ca3af;">
                          This message was sent by the NexGenAI Recruitment Platform.</p>
                      </td>
                    </tr>
                  </table>
                </td></tr>
              </table>
            </body></html>
            """.formatted(
                c.getFirstName(), job.getTitle(),
                job.getDepartment() != null ? job.getDepartment() + " •" : "",
                job.getLocation() != null ? job.getLocation() : "",
                score != null ? buildScoreBadge(score) : "",
                next, note
            );
    }

    private String buildScoreBadge(int score) {
        String color;
        if (score >= 80) {
            color = "#10b981";
        } else if (score >= 60) {
            color = "#f59e0b";
        } else {
            color = "#ef4444";
        }
        return """
            <div style="display:inline-block;background:%s18;border:1px solid %s40;
                        border-radius:50px;padding:8px 20px;margin:0 0 0;">
              <span style="color:%s;font-size:15px;font-weight:700;">
                🤖 AI Match Score: %d%%</span>
            </div>""".formatted(color, color, color, score);
    }

    private String buildRejectionHtml(Candidate c, Job job, String hrNote) {
        String note = (hrNote != null && !hrNote.isBlank())
            ? "<div style='margin:24px 0 0;padding:16px 20px;background:#fff7ed;"
              + "border-left:3px solid #f97316;border-radius:8px;'>"
              + "<p style='margin:0;font-size:13px;font-weight:600;color:#9a3412;'>Feedback</p>"
              + "<p style='margin:8px 0 0;font-size:14px;color:#7c2d12;'>" + hrNote + "</p></div>"
            : "";

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;padding:0;background:#f8fafc;font-family:'Segoe UI',Arial,sans-serif;">
              <table width="100%%" cellpadding="0" cellspacing="0" style="background:#f8fafc;padding:40px 0;">
                <tr><td align="center">
                  <table width="560" cellpadding="0" cellspacing="0"
                         style="background:#ffffff;border-radius:16px;overflow:hidden;
                                box-shadow:0 4px 24px rgba(0,0,0,0.08);">
                    <tr>
                      <td style="background:linear-gradient(135deg,#475569 0%%,#334155 100%%);
                                 padding:36px 40px;text-align:center;">
                        <h1 style="margin:0;color:#ffffff;font-size:22px;font-weight:700;">
                          Application Update</h1>
                        <p style="margin:10px 0 0;color:#cbd5e1;font-size:15px;">
                          Regarding your application at NexGenAI</p>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:36px 40px;">
                        <p style="margin:0 0 16px;color:#374151;font-size:15px;line-height:1.6;">
                          Dear <strong>%s</strong>,</p>
                        <p style="margin:0 0 24px;color:#374151;font-size:15px;line-height:1.6;">
                          Thank you for your interest in the <strong>%s</strong> position and for
                          taking the time to go through our recruitment process.</p>
                        <p style="margin:0 0 24px;color:#374151;font-size:15px;line-height:1.6;">
                          After careful review of your application and profile analysis, we regret
                          to inform you that we will not be moving forward with your candidacy at
                          this time.</p>
                        %s
                        <p style="margin:24px 0 0;color:#6b7280;font-size:14px;line-height:1.6;">
                          We sincerely appreciate your interest and encourage you to apply for
                          future opportunities that match your profile.</p>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:20px 40px 36px;text-align:center;border-top:1px solid #f1f5f9;">
                        <p style="margin:0;font-size:12px;color:#9ca3af;">
                          This message was sent by the NexGenAI Recruitment Platform.</p>
                      </td>
                    </tr>
                  </table>
                </td></tr>
              </table>
            </body></html>
            """.formatted(c.getFirstName(), job.getTitle(), note);
    }
}
