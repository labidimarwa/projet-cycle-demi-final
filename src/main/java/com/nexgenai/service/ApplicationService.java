package com.nexgenai.service;

import com.nexgenai.model.Application;
import com.nexgenai.model.Candidate;
import com.nexgenai.model.enums.NotificationType;
import com.nexgenai.repository.ApplicationRepository;
import com.nexgenai.repository.JobRepository;
import com.nexgenai.repository.UserRepository;
import com.nexgenai.security.FileSecurityValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.CompletableFuture;

/**
 * Handles candidate applications.
 * On submit: saves Application, triggers async matching, and SEEDS process stages.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationService {

    private final ApplicationRepository           applicationRepository;
    private final UserRepository                  userRepository;
    private final JobRepository                   jobRepository;
    private final CvMatchingService               cvMatchingService;
    private final ApplicationStageProgressService stageProgressService;
    private final FileStorageService              fileStorageService;
    private final FileSecurityValidator           fileSecurityValidator;
    private final NotificationService             notificationService;

    @Transactional
    public void submitApplication(String candidateEmail, String jobId,
                                  MultipartFile cv, MultipartFile audio) {

        Candidate candidate = findCandidate(candidateEmail);

        if (applicationRepository.existsByCandidateIdAndJobId(candidate.getId(), jobId)) {
            throw new IllegalStateException("Vous avez déjà postulé à cette offre.");
        }

        Application.ApplicationBuilder builder = Application.builder()
            .candidateId(candidate.getId())
            .jobId(jobId);

        // Persist CV if provided
        if (cv != null && !cv.isEmpty()) {
            FileSecurityValidator.ValidationResult vr = fileSecurityValidator.validate(cv, extractClientIp());
            if (!vr.valid()) throw new IllegalArgumentException("CV file rejected: " + vr.reason());
            String cvPath = fileStorageService.saveFile(candidateEmail, cv);
            builder.cvPath(cvPath);
            candidate.setCvPath(cvPath);
            userRepository.save(candidate);
        }

        Application savedApp = applicationRepository.save(builder.build());
        log.info("✅ Application saved: {} → job {}", candidateEmail, jobId);

        // ── Seed HR process-mapping stages for this application ───────────────
        stageProgressService.seedFromWorkflowStages(candidate.getId(), jobId);
        log.info("📋 Stage progress seeded: candidate {} → job {}", candidate.getId(), jobId);

        // ── Notify HR of new application ─────────────────────────────────────
        String candidateName = candidate.getFirstName() + " " + candidate.getLastName();
        notifyHr(jobId, NotificationType.NEW_APPLICATION,
            "New Application",
            candidateName + " just applied to \"%s\".");

        // ── Trigger async AI matching ────────────────────────────────────────
        String candidateId = candidate.getId();
        CompletableFuture.runAsync(() -> {
            try {
                cvMatchingService.lancerMatching(jobId, candidateId, null, null);
                log.info("✅ Matching IA terminé : {} → job {}", candidateEmail, jobId);

                // Notify HR that match score is ready
                notifyHr(jobId, NotificationType.MATCH_READY,
                    "AI Match Ready",
                    "Match score computed for " + candidateName + " on \"%s\".");

                // Notify candidate that their match is visible
                notificationService.send(
                    candidateId, NotificationType.MATCH_COMPUTED,
                    "Your Match Score is Ready",
                    "Your AI compatibility score for the applied position has been computed.",
                    jobId, "JOB", "/candidate/applications"
                );

            } catch (Exception e) {
                log.warn("⚠️ Matching IA échoué (retentable via SSE) : {}", e.getMessage());
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractClientIp() {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                var req = attrs.getRequest();
                String xff = req.getHeader("X-Forwarded-For");
                if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
                return req.getRemoteAddr();
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    private void notifyHr(String jobId, NotificationType type, String title, String bodyTemplate) {
        jobRepository.findById(jobId).ifPresent(job -> {
            if (job.getCreatedByHrId() != null) {
                notificationService.send(
                    job.getCreatedByHrId(), type, title,
                    String.format(bodyTemplate, job.getTitle()),
                    jobId, "JOB", "/hr/jobs/" + jobId + "/candidates"
                );
            }
        });
    }

    private Candidate findCandidate(String email) {
        return userRepository.findByEmail(email)
            .filter(u -> u instanceof Candidate)
            .map(u -> (Candidate) u)
            .orElseThrow(() -> new RuntimeException("Candidate not found: " + email));
    }
}