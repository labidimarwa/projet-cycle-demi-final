package com.nexgenai.service;

import com.nexgenai.model.Application;
import com.nexgenai.model.Candidate;
import com.nexgenai.repository.ApplicationRepository;
import com.nexgenai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
    private final MatchingService                 matchingService;
    private final ApplicationStageProgressService stageProgressService;
    private final FileStorageService              fileStorageService;

    @Transactional
    public void submitApplication(String candidateEmail, String jobId,
                                  MultipartFile cv, MultipartFile audio) {

        Candidate candidate = findCandidate(candidateEmail);

        // Idempotent: ignore duplicate applications
        if (applicationRepository.existsByCandidateIdAndJobId(candidate.getId(), jobId)) {
            log.info("⚠️  Duplicate application ignored: {} → {}", candidateEmail, jobId);
            return;
        }

        Application.ApplicationBuilder builder = Application.builder()
            .candidateId(candidate.getId())
            .jobId(jobId);

        // Persist CV if provided
        if (cv != null && !cv.isEmpty()) {
            String cvPath = fileStorageService.saveFile(candidateEmail, cv);
            builder.cvPath(cvPath);
            candidate.setCvPath(cvPath);
            userRepository.save(candidate);
        }

        applicationRepository.save(builder.build());
        log.info("✅ Application saved: {} → job {}", candidateEmail, jobId);

        // ── Seed HR process-mapping stages for this application ───────────────
        // Creates one ApplicationStageProgress row per WorkflowStage of the job.
        // First stage (AI_SCREENING) is set to IN_PROGRESS automatically.
        stageProgressService.seedFromWorkflowStages(candidate.getId(), jobId);
        log.info("📋 Stage progress seeded: candidate {} → job {}", candidate.getId(), jobId);

        // ── Trigger async AI matching ─────────────────────────────────────────
        matchingService.computeMatchForCandidateAndJob(candidateEmail, jobId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Candidate findCandidate(String email) {
        return userRepository.findByEmail(email)
            .filter(u -> u instanceof Candidate)
            .map(u -> (Candidate) u)
            .orElseThrow(() -> new RuntimeException("Candidate not found: " + email));
    }
}