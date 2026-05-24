package com.nexgenai.service;

import com.nexgenai.model.Candidate;
import com.nexgenai.model.Job;
import com.nexgenai.model.JobMatch;
import com.nexgenai.model.enums.JobStatus;
import com.nexgenai.repository.CandidateRepository;
import com.nexgenai.repository.JobMatchRepository;
import com.nexgenai.repository.JobRepository;
import com.nexgenai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.apache.pdfbox.Loader;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchingService {

    private final JobMatchRepository   jobMatchRepository;
    private final JobRepository        jobRepository;
    private final UserRepository       userRepository;
    private final OllamaMatchingService ollamaService;
private final CandidateRepository candidateRepo;
private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.cv.upload-dir:uploads/cv}")
    private String uploadDir;

    
    
    
    
    
    // ─── Appelé après upload du CV — lance le scoring en arrière-plan ─────────
  

    public boolean isAiAvailable() {
        return ollamaService.isAvailable();
    }

    @Async("matchingExecutor")
    public CompletableFuture<OllamaMatchingService.MatchResult> computeAsync(String candidateEmail, String jobId) {
        try {
            JobMatch match = computeMatchForCandidateAndJob(candidateEmail, jobId);
            OllamaMatchingService.MatchResult r = new OllamaMatchingService.MatchResult();
            r.setScoreGlobal(match.getScore());
            r.setVerdict(match.getVerdict());
            r.setResume(match.getResume());
            r.setDimensionsJson(match.getDimensionsJson());
            r.setSkillsMatched(match.getSkillsMatched());
            r.setSkillsMissing(match.getSkillsMissing());
            return CompletableFuture.completedFuture(r);
        } catch (Exception e) {
            log.error("❌ Async compute failed: {}", e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }


    public JobMatch computeMatchForCandidateAndJob(String candidateEmail, String jobId) {
        Candidate candidate = findCandidate(candidateEmail);
        if (candidate == null)
            throw new RuntimeException("Candidate not found: " + candidateEmail);
        if (candidate.getCvPath() == null)
            throw new RuntimeException("No CV uploaded");

        String cvText = extractCvText(candidate);
        if (cvText == null || cvText.isBlank())
            throw new RuntimeException("CV is empty or unreadable");

        String cvHash = md5(cvText);

        // Cache hit → retourner directement sans appeler Ollama
        Optional<JobMatch> cached = jobMatchRepository
            .findByCandidateIdAndJobId(candidate.getId(), jobId)
            .filter(m -> cvHash.equals(m.getCvHash()) && m.getScore() != null);
        
        if (cached.isPresent()) return cached.get();

        // Sinon lancer le calcul
        Job jobRef = new Job();
        jobRef.setId(jobId);
        processJobMatch(candidate, jobRef, cvText, cvHash);  // REQUIRES_NEW = sa propre transaction

        return jobMatchRepository
            .findByCandidateIdAndJobId(candidate.getId(), jobId)
            .orElseThrow(() -> new RuntimeException("Match computation failed"));
    }
    
  
    // ─── Récupère les scores en cache pour un candidat ────────────────────────
    public List<JobMatch> getMatchesForCandidate(String candidateEmail) {
        Candidate candidate = findCandidate(candidateEmail);
        if (candidate == null) return List.of();
        return jobMatchRepository.findByCandidateId(candidate.getId());
    }

    // ─── Lit le texte du PDF ──────────────────────────────────────────────────
    private String extractCvText(Candidate candidate) {
        try {
            String storedPath = candidate.getCvPath();
            // Format: "NomOriginal.pdf|nomFichier.pdf"
            String fileName = storedPath.contains("|")
                ? storedPath.split("\\|")[1]
                : candidate.getEmail().replaceAll("[^a-zA-Z0-9._-]", "_")
                  + (storedPath.contains(".") ? storedPath.substring(storedPath.lastIndexOf(".")) : ".pdf");

            File cvFile = Paths.get(uploadDir).toAbsolutePath().resolve(fileName).toFile();

            if (!cvFile.exists()) {
                log.warn("❌ Fichier CV introuvable : {}", cvFile.getAbsolutePath());
                return null;
            }

            // Extraction texte selon extension
            String ext = fileName.toLowerCase();
            if (ext.endsWith(".pdf")) {
            	try (PDDocument doc = Loader.loadPDF(cvFile)) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    return stripper.getText(doc);
                }
            } else {
                // Pour .doc/.docx — retourner le contenu brut (moins précis)
                return new String(Files.readAllBytes(cvFile.toPath()));
            }

        } catch (Exception e) {
            log.error("❌ Erreur extraction CV : {}", e.getMessage());
            return null;
        }
    }


    // ─── Helpers ──────────────────────────────────────────────────────────────
    private Candidate findCandidate(String email) {
        return userRepository.findByEmail(email)
            .filter(u -> u instanceof Candidate)
            .map(u -> (Candidate) u)
            .orElse(null);
    }

    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(text.getBytes()));
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
    
    
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processJobMatch(Candidate candidate, Job jobRef, String cvText, String cvHash) {
        try {
            // Deux requêtes séparées pour éviter MultipleBagFetchException
            Job jobWithSkills = jobRepository.findByIdWithSkills(jobRef.getId())
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobRef.getId()));
            Job jobWithPrereqs = jobRepository.findByIdWithPrerequisites(jobRef.getId())
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobRef.getId()));

            log.info("🤖 Calcul score : {} ↔ {}", candidate.getEmail(), jobWithSkills.getTitle());

            // Construire le texte de l'offre manuellement ici
            String offerText = buildOfferText(jobWithSkills, jobWithPrereqs);

            OllamaMatchingService.MatchResult result;
            try {
                result = ollamaService.analyze(cvText, offerText);
            } catch (Exception e) {
                log.warn("⚠️ Ollama indisponible, fallback scoring pour jobId={}", jobRef.getId());
                result = ollamaService.computeKeywordScore(cvText, jobWithSkills.getTechnicalSkills());
            }

            JobMatch match = jobMatchRepository
                .findByCandidateIdAndJobId(candidate.getId(), jobRef.getId())
                .orElse(JobMatch.builder()
                    .candidateId(candidate.getId())
                    .jobId(jobRef.getId())
                    .build());

            match.setScore(result.getScoreGlobal());
            match.setVerdict(result.getVerdict());
            match.setResume(result.getResume());
            match.setSkillsMatched(result.getSkillsMatched());
            match.setSkillsMissing(result.getSkillsMissing());
            match.setDimensionsJson(result.getDimensionsJson());
            match.setComputedAt(LocalDateTime.now());
            match.setCvHash(cvHash);

            jobMatchRepository.save(match);
            log.info("✅ Score {} sauvegardé pour '{}'", result.getScoreGlobal(), jobWithSkills.getTitle());

        } catch (Exception e) {
            log.error("❌ Erreur traitement job {}: {}", jobRef.getId(), e.getMessage(), e);
        }
    }
    
    
    private String buildOfferText(Job job, Job jobWithPrereqs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Titre: ").append(job.getTitle()).append("\n");
        sb.append("Département: ").append(job.getDepartment()).append("\n");
        sb.append("Localisation: ").append(job.getLocation() != null ? job.getLocation() : "Non précisé").append("\n");
        sb.append("Contrat: ").append(job.getContractType()).append("\n");
        sb.append("Expérience: ").append(job.getExperienceLevel()).append("\n");
        sb.append("Description: ").append(job.getDescription() != null ? job.getDescription() : "").append("\n");

        if (job.getTechnicalSkills() != null && !job.getTechnicalSkills().isEmpty()) {
            sb.append("Compétences requises: ");
            job.getTechnicalSkills().forEach(s -> sb.append(s.getName()).append(", "));
            sb.append("\n");
        }

        if (jobWithPrereqs.getPrerequisites() != null && !jobWithPrereqs.getPrerequisites().isEmpty()) {
            sb.append("Prérequis: ");
            jobWithPrereqs.getPrerequisites().forEach(p ->
                sb.append(p.getType()).append(": ").append(p.getValue()).append(", "));
            sb.append("\n");
        }

        return sb.toString();
    }
    
    
    
   
    
    
}