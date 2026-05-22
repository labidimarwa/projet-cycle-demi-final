package com.nexgenai.service;

import com.nexgenai.dto.candidate.CandidateApplicationResponse;
import com.nexgenai.dto.candidate.CandidateProfileResponse;
import com.nexgenai.dto.candidate.StageProgressDTO;
import com.nexgenai.dto.candidate.UpdateCandidateProfileRequest;
import com.nexgenai.model.*;
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
    private final MatchingService                     matchingService;
    private final CandidateApplicationService    candidateApplicationService; // ✅ INJECTÉ


    // Injected to avoid circular dependency: CandidateService ↔ ApplicationStageProgressService
    private final ApplicationStageProgressService     stageProgressService;

    @Value("${app.cv.upload-dir:uploads/cv}")
    private String uploadDir;

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
        String cvPath = saveCvFile(email, file);
        c.setCvPath(cvPath);
        userRepository.save(c);
        return cvPath;
        // matching déclenché séparément ↓
    }

    public Path getCvFilePath(String email) {
        Candidate c = findCandidate(email);
        if (c.getCvPath() == null) throw new RuntimeException("No CV uploaded");
        String[] parts = c.getCvPath().split("\\|");
        String fileName = parts.length > 1 ? parts[1] : parts[0];
        return Paths.get(uploadDir).toAbsolutePath().resolve(fileName);
    }

    public String getCvDisplayName(String email) {
        Candidate c = findCandidate(email);
        if (c.getCvPath() == null) return "cv.pdf";
        String[] parts = c.getCvPath().split("\\|");
        return parts[0];
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
   /* @Transactional
    public List<CandidateApplicationResponse> getApplications(String email) {
        Candidate c = findCandidate(email);

        return applicationRepository.findByCandidateId(c.getId()).stream()
            .map(app -> buildApplicationResponse(c, app))
            .collect(Collectors.toList());
    }
    */
    
    
    @Transactional(readOnly = true)
    public List<CandidateApplicationResponse> getApplications(String email) {
    	   Candidate c = findCandidate(email);
        return candidateApplicationService.getApplicationsForCandidate(c.getId());
    }
    

    // ── Build one application response ────────────────────────────────────────

    private CandidateApplicationResponse buildApplicationResponse(Candidate c, Application app) {

        // Job info
        Job job = jobRepository.findById(app.getJobId()).orElse(null);
        String jobTitle      = job != null ? job.getTitle()                  : "Unknown Job";
        String department    = job != null ? job.getDepartment()             : "";
        String location      = job != null ? job.getLocation()               : "";
        String contractType  = job != null ? (job.getContractType()  != null ? job.getContractType().name()  : "") : "";
        String expLevel      = job != null ? (job.getExperienceLevel()!= null ? job.getExperienceLevel().name(): "") : "";

        // Match score
        Optional<JobMatch> match = jobMatchRepository
            .findByCandidateIdAndJobId(c.getId(), app.getJobId());
        Integer matchScore    = match.map(JobMatch::getScore).orElse(null);
        boolean matchComputed = match.isPresent() && matchScore != null;

        // Chat score (from ChatSession if done)
        Integer chatScore = null;
        boolean chatDone  = false;
        // (wire to ChatSessionRepository if you have extractScore stored)

        // Tests — wire to your TestSession / PsychometricTest repos here
        List<CandidateApplicationResponse.JobTestInfo> tests = List.of();

        // ── Stage progress (HR-defined process mapping) ───────────────────────
        // getProgress seeds rows on first call if they don't exist yet
        List<ApplicationStageProgress> rows =
            stageProgressService.getProgress(c.getId(), app.getJobId());

        List<StageProgressDTO> stageProgress = rows.stream()
            .map(this::mapStageProgress)
            .collect(Collectors.toList());

        return CandidateApplicationResponse.builder()
            .jobId(app.getJobId())
            .jobTitle(jobTitle)
            .department(department)
            .location(location)
            .contractType(contractType)
            .experienceLevel(expLevel)
            .applicationStatus(app.getStatus() != null ? app.getStatus().name() : "PENDING")
            .appliedAt(app.getAppliedAt())
            .matchScore(matchScore)
            .matchComputed(matchComputed)
            .chatDone(chatDone)
            .chatScore(chatScore)
            .tests(tests)
            .stageProgress(stageProgress)
            .build();
    }

    // ── Mapper ────────────────────────────────────────────────────────────────

    private StageProgressDTO mapStageProgress(ApplicationStageProgress p) {
        return new StageProgressDTO(
            p.getId(),
            p.getStageOrder(),
            p.getStageName(),
            p.getStageType(),
            p.getStatus().name(),
            p.getStartedAt()   != null ? p.getStartedAt().toString()   : null,
            p.getCompletedAt() != null ? p.getCompletedAt().toString() : null,
            p.getHrNote()
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String saveCvFile(String email, MultipartFile file) {
        String originalName = file.getOriginalFilename() != null
            ? file.getOriginalFilename() : "cv.pdf";
        String ext = originalName.contains(".")
            ? originalName.substring(originalName.lastIndexOf(".")).toLowerCase() : ".pdf";
        if (!List.of(".pdf", ".doc", ".docx").contains(ext))
            throw new RuntimeException("Unsupported format.");
        if (file.getSize() > 5 * 1024 * 1024)
            throw new RuntimeException("File too large (max 5 MB).");
        try {
            Path dir = Paths.get(uploadDir).toAbsolutePath();
            Files.createDirectories(dir);
            String safeEmail = email.replaceAll("[^a-zA-Z0-9._-]", "_");
            String fileName  = safeEmail + ext;
            Files.copy(file.getInputStream(), dir.resolve(fileName),
                       StandardCopyOption.REPLACE_EXISTING);
            return originalName + "|" + fileName;
        } catch (IOException e) {
            throw new RuntimeException("CV upload error: " + e.getMessage(), e);
        }
    }

    private Candidate findCandidate(String email) {
        return candidateRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("Candidate not found: " + email));
    }
    
    
    @Transactional
    public JobMatch computeSingleMatch(String email, String jobId) {
        Candidate c = findCandidate(email);
        if (c.getCvPath() == null)
            throw new RuntimeException("No CV uploaded");
        // Déclenche le calcul pour un seul job et retourne le résultat
        return matchingService.computeMatchForCandidateAndJob(email, jobId);
    }
}