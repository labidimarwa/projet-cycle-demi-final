// ══════════════════════════════════════════════════════════════════════════════
// FILE: src/main/java/com/nexgenai/service/ApplicationStageProgressService.java  (NEW)
// ══════════════════════════════════════════════════════════════════════════════
package com.nexgenai.service;

import com.nexgenai.model.ApplicationStageProgress;
import com.nexgenai.model.Job;
import com.nexgenai.model.enums.NotificationType;
import com.nexgenai.model.enums.StageProgressStatus;
import com.nexgenai.model.enums.StageType;
import com.nexgenai.repository.ApplicationStageProgressRepository;
import com.nexgenai.repository.ChatSessionRepository;
import com.nexgenai.repository.JobMatchRepository;
import com.nexgenai.repository.JobRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ApplicationStageProgressService {

    private final ApplicationStageProgressRepository progressRepo;
    private final JobRepository                      jobRepo;
    private final JobMatchRepository                 jobMatchRepo;
    private final ChatSessionRepository              chatSessionRepo;
    private final NotificationService                notificationService;

    public ApplicationStageProgressService(
            ApplicationStageProgressRepository progressRepo,
            JobRepository jobRepo,
            JobMatchRepository jobMatchRepo,
            ChatSessionRepository chatSessionRepo,
            @Lazy NotificationService notificationService) {
        this.progressRepo        = progressRepo;
        this.jobRepo             = jobRepo;
        this.jobMatchRepo        = jobMatchRepo;
        this.chatSessionRepo     = chatSessionRepo;
        this.notificationService = notificationService;
    }

    // ── READ ──────────────────────────────────────────────────────────────────

    /**
     * Returns the ordered list of stage-progress rows for a (candidate, job) pair.
     * If rows don't exist yet (legacy applications), they are seeded on-the-fly.
     */
    @Transactional
    public List<ApplicationStageProgress> getProgress(String candidateId, String jobId) {
        if (!progressRepo.existsByCandidateIdAndJobId(candidateId, jobId)) {
            seedFromWorkflowStages(candidateId, jobId);
        }
        List<ApplicationStageProgress> rows =
                progressRepo.findByCandidateAndJob(candidateId, jobId);

        // Auto-advance: sync statuses from existing data sources
        autoAdvance(rows, candidateId, jobId);
        return progressRepo.findByCandidateAndJob(candidateId, jobId);
    }

    // ── SEED ──────────────────────────────────────────────────────────────────

    /**
     * Called when a candidate applies to a job.
     * Creates one ApplicationStageProgress row per WorkflowStage of the job.
     * The first stage (AI_SCREENING) is set to IN_PROGRESS automatically.
     */
    @Transactional
    public void seedFromWorkflowStages(String candidateId, String jobId) {
        if (progressRepo.existsByCandidateIdAndJobId(candidateId, jobId)) return;

        Job job = jobRepo.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

        List<ApplicationStageProgress> rows = job.getWorkflowStages().stream()
                .sorted((a, b) -> Integer.compare(
                        a.getStageOrder() != null ? a.getStageOrder() : 0,
                        b.getStageOrder() != null ? b.getStageOrder() : 0))
                .map(stage -> {
                    ApplicationStageProgress p = new ApplicationStageProgress();
                    p.setCandidateId(candidateId);
                    p.setJobId(jobId);
                    p.setStageOrder(stage.getStageOrder() != null ? stage.getStageOrder() : 1);
                    p.setStageName(stage.getName());
                    p.setStageType(stage.getStageType() != null ? stage.getStageType().name() : "AI_SCREENING");
                    p.setStatus(StageProgressStatus.PENDING);
                    return p;
                })
                .toList();

        // First stage starts immediately
        if (!rows.isEmpty()) {
            rows.get(0).setStatus(StageProgressStatus.IN_PROGRESS);
            rows.get(0).setStartedAt(LocalDateTime.now());
        }

        progressRepo.saveAll(rows);
    }

    // ── AUTO-ADVANCE ──────────────────────────────────────────────────────────

    /**
     * Inspects existing data sources (JobMatch, ChatSession, TestSession) and
     * advances stages to COMPLETED / IN_PROGRESS automatically.
     */
    @Transactional
    public void autoAdvance(List<ApplicationStageProgress> rows, String candidateId, String jobId) {
        boolean changed = false;
        for (ApplicationStageProgress row : rows) {
            if (row.getStatus() == StageProgressStatus.COMPLETED) continue;

            boolean done = isStageNaturallyDone(row, candidateId, jobId);
            if (done) {
                row.setStatus(StageProgressStatus.COMPLETED);
                row.setCompletedAt(LocalDateTime.now());
                changed = true;
            }
        }

        if (changed) {
            progressRepo.saveAll(rows);
            // After saving completions, activate the next PENDING stage
            activateNextPending(rows);
        }
    }

    private boolean isStageNaturallyDone(ApplicationStageProgress row, String candidateId, String jobId) {
        String type = row.getStageType();
        if (type == null) return false;

        return switch (type) {
            case "AI_SCREENING" ->
                // Done when JobMatch score has been computed
                jobMatchRepo.findByJobId(jobId).stream()
                    .anyMatch(m -> m.getScore() != null && m.getScore() > 0
                               && candidateId.equals(m.getCandidateId()));
            case "RH_INTERVIEW", "RH_TEST" ->
                // Done when HR manually sets COMPLETED (no auto signal available)
                row.getStatus() == StageProgressStatus.COMPLETED;
            case "TECHNICAL_TEST", "TECHNICAL_INTERVIEW", "ADMIN_INTERVIEW" ->
                // Done when HR manually sets COMPLETED
                row.getStatus() == StageProgressStatus.COMPLETED;
            default -> false;
        };
    }

    private void activateNextPending(List<ApplicationStageProgress> rows) {
        for (ApplicationStageProgress row : rows) {
            if (row.getStatus() == StageProgressStatus.PENDING) {
                row.setStatus(StageProgressStatus.IN_PROGRESS);
                row.setStartedAt(LocalDateTime.now());
                progressRepo.save(row);
                break; // Only activate the immediate next one
            }
        }
    }

    // ── HR MANUAL ADVANCE ─────────────────────────────────────────────────────

    /**
     * HR manually marks a stage as COMPLETED or PENDING (undo).
     * Called from HrController.
     */
    @Transactional
    public ApplicationStageProgress updateStageStatus(
            String candidateId, String jobId,
            int stageOrder, StageProgressStatus newStatus, String hrNote) {

        List<ApplicationStageProgress> rows =
                progressRepo.findByCandidateAndJob(candidateId, jobId);

        ApplicationStageProgress target = rows.stream()
                .filter(r -> r.getStageOrder() == stageOrder)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Stage not found"));

        target.setStatus(newStatus);
        target.setHrNote(hrNote);
        if (newStatus == StageProgressStatus.COMPLETED && target.getCompletedAt() == null) {
            target.setCompletedAt(LocalDateTime.now());
        }

        progressRepo.save(target);

        // Activate next if we just completed this one
        if (newStatus == StageProgressStatus.COMPLETED) {
            activateNextPending(rows);

            // Find the newly activated stage and notify the candidate
            rows.stream()
                .filter(r -> r.getStageOrder() > stageOrder
                          && r.getStatus() == StageProgressStatus.IN_PROGRESS)
                .findFirst()
                .ifPresent(next -> {
                    String type = next.getStageType();
                    if ("TECHNICAL_TEST".equals(type) || "RH_TEST".equals(type)) {
                        notificationService.send(
                            candidateId, NotificationType.TEST_ASSIGNED,
                            "New Test Available",
                            "You have a new test to complete: \"" + next.getStageName() + "\".",
                            jobId, "JOB", "/candidate/applications"
                        );
                    } else if ("RH_INTERVIEW".equals(type) || "TECHNICAL_INTERVIEW".equals(type)
                            || "ADMIN_INTERVIEW".equals(type)) {
                        notificationService.send(
                            candidateId, NotificationType.INTERVIEW_SCHEDULED,
                            "Interview Stage Activated",
                            "Your next step is an interview: \"" + next.getStageName() + "\".",
                            jobId, "JOB", "/candidate/applications"
                        );
                    } else {
                        notificationService.send(
                            candidateId, NotificationType.STAGE_COMPLETED,
                            "Stage Advanced",
                            "You have been moved to the next stage: \"" + next.getStageName() + "\".",
                            jobId, "JOB", "/candidate/applications"
                        );
                    }
                });
        }

        return target;
    }
}