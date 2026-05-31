package com.nexgenai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexgenai.dto.matching.*;
import com.nexgenai.model.*;
import com.nexgenai.repository.CandidateRepository;
import com.nexgenai.repository.JobMatchRepository;
import com.nexgenai.repository.JobRepository;
import com.nexgenai.repository.MatchingReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrateur du matching CV ↔ Offre (nouvelle architecture Python + Java).
 *
 * Workflow :
 *  1. Appel Python /extract (job-aware Qwen) → compétences + embeddings multilingues
 *  2. Appel Python /embed   → embeddings skills du poste (multilingue FR+EN)
 *  3. Similarité cosinus multilingue pour chaque skill requis
 *  4. Application règles de rejet forcé — score = 0 si obligatoire manquant
 *  5. Score global pondéré (weights viennent du job créé par le RH)
 *  6. Sauvegarde BDD (upsert par jobId + candidateId)
 *
 * Contrainte 1 : les weights viennent TOUJOURS du job — jamais codés en dur.
 * Contrainte 2 : skill obligatoire + similarité < 0.50 → score = 0 (rejet forcé).
 * Contrainte 3 : prérequis obligatoire + match < 0.40 → score = 0 (rejet forcé).
 * Contrainte 4 : Python indisponible → RuntimeException message clair.
 * Contrainte 5 : seule la similarité cosinus est calculée ici (Java).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CvMatchingService {

    private final PythonExtractorClient    pythonClient;
    private final JobRepository            jobRepository;
    private final MatchingReportRepository reportRepository;
    private final JobMatchRepository       jobMatchRepository;
    private final CandidateRepository      candidateRepository;
    private final ObjectMapper             objectMapper;

    @Value("${app.cv.upload-dir:uploads/cv}")
    private String uploadDir;

    // ── Seuils configurables (application.properties) ────────────────────────
    @Value("${matching.similarity.threshold.match:0.75}")
    private double seuilMatch;

    @Value("${matching.similarity.threshold.partial:0.50}")
    private double seuilPartiel;

    @Value("${matching.recommendation.retenir:75}")
    private double seuilRetenir;

    @Value("${matching.recommendation.etudier:50}")
    private double seuilEtudier;


    // ═════════════════════════════════════════════════════════════════════════
    // Point d'entrée principal
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Lance le matching complet pour un candidat et un poste.
     * Peut être appelé avec un CV fourni ou en lisant le CV stocké en BDD/disque.
     *
     * @param jobId       identifiant du poste
     * @param candidateId identifiant UUID du candidat
     * @param cvBytes     contenu binaire du CV (null → utilise le CV stocké)
     * @param cvFilename  nom du fichier CV
     * @return rapport de matching complet prêt pour Angular
     */
    @Transactional
    public MatchingReportDTO lancerMatching(
            String jobId,
            String candidateId,
            byte[] cvBytes,
            String cvFilename) {

        // ── Vérification disponibilité Python (Contrainte 4) ─────────────────
        if (!pythonClient.isAvailable()) {
            throw new RuntimeException(
                "Le microservice d'extraction IA (Python) est indisponible. " +
                "Lancez : uvicorn main:app --host 0.0.0.0 --port 8000"
            );
        }

        // ── Chargement du candidat ────────────────────────────────────────────
        Candidate candidat = candidateRepository.findById(candidateId)
            .orElseThrow(() -> new RuntimeException("Candidat introuvable : " + candidateId));

        // ── Chargement CV depuis le disque si non fourni ──────────────────────
        if (cvBytes == null || cvBytes.length == 0) {
            cvBytes   = lireCvDuDisque(candidat);
            cvFilename = nomFichierCv(candidat);
        }
        if (cvBytes == null || cvBytes.length == 0) {
            throw new RuntimeException("Aucun CV disponible pour ce candidat.");
        }

        // ── Cache : même CV + même poste → rapport existant (avant appel Python) ──
        String cvHash = md5(cvBytes);
        // ── Chargement du poste (deux requêtes pour éviter MultipleBagFetchException) ──
        Job jobAvecSkills = jobRepository.findByIdWithSkills(jobId)
            .orElseThrow(() -> new RuntimeException("Poste introuvable : " + jobId));
        Job jobAvecPrereqs = jobRepository.findByIdWithPrerequisites(jobId)
            .orElseThrow(() -> new RuntimeException("Poste introuvable : " + jobId));

        Optional<MatchingReport> cached = reportRepository
            .findByJobIdAndCandidateIdAndCvHash(jobId, candidateId, cvHash);
        if (cached.isPresent()) {
            log.info("⚡ Rapport en cache retourné (CV inchangé) — Python non appelé");
            MatchingReport r = cached.get();
            // Ensure job_matches stays in sync (may be empty on first run after migration)
            if (!jobMatchRepository.existsByCandidateIdAndJobIdAndCvHash(candidateId, jobId, cvHash)) {
                JobMatch jm = jobMatchRepository
                    .findByCandidateIdAndJobId(candidateId, jobId)
                    .orElse(JobMatch.builder().candidateId(candidateId).jobId(jobId).build());
                jm.setScore((int) Math.round(r.getScoreGlobal()));
                jm.setVerdict(r.getRecommendation());
                jm.setCvHash(cvHash);
                jm.setComputedAt(r.getComputedAt());
                jobMatchRepository.save(jm);
            }
            return deserialiserRapport(r, jobAvecSkills, candidat);
        }

        log.info("🎯 Matching sémantique : {} ↔ {}", candidat.getEmail(), jobAvecSkills.getTitle());

        // ── 1. Build job skills list for Python ───────────────────────────────
        List<Map<String, Object>> skillsMaps = jobAvecSkills.getTechnicalSkills().stream()
            .filter(s -> s.getName() != null)
            .map(s -> {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("nom",       s.getName());
                m.put("type",      s.getSkillType() != null ? s.getSkillType() : "TECHNICAL");
                m.put("obligatoire", Boolean.TRUE.equals(s.getObligatory()));
                return m;
            })
            .collect(Collectors.toList());

        // ── 2. Python : MiniLM (skills) + RAG+Qwen (prérequis) ───────────────
        // jobId passed so Python can reuse pre-indexed job embeddings (computed at job creation)
        CvExtractionResult extraction = pythonClient.extraireCv(
                cvBytes, cvFilename, jobAvecPrereqs.getPrerequisites(), skillsMaps, jobId
        );

        	// ← AJOUTE ICI
        	log.info("📥 Raw extraction reçue — skillsEvalues={} prerequisEvalues={}",
        	    extraction.getSkillsEvalues(),
        	    extraction.getPrerequisEvalues()
        	);
        

        List<CvExtractionResult.SkillEvalue>    skillsEvalues  = extraction.getSkillsEvalues()    != null ? extraction.getSkillsEvalues()    : Collections.emptyList();
        List<CvExtractionResult.PrerequisEvalue> prereqsEvalues = extraction.getPrerequisEvalues() != null ? extraction.getPrerequisEvalues() : Collections.emptyList();

        log.info("📄 Python → {} skills évalués, {} prérequis évalués",
            skillsEvalues.size(), prereqsEvalues.size());

        // ── 3. Scoring des compétences ────────────────────────────────────────
        List<SkillMatchResult> resultatsSkills = scorerCompetences(
            jobAvecSkills.getTechnicalSkills(), skillsEvalues
        );

        // ── 4a. Rejet forcé — skill obligatoire non satisfait (Contrainte 2) ──
        Optional<SkillMatchResult> rejetSkill = resultatsSkills.stream()
            .filter(r -> r.isObligatoire() && r.getSimilarite() < seuilPartiel)
            .findFirst();

        // ── 5. Scoring des prérequis ──────────────────────────────────────────
        List<PrerequisiteMatchResult> resultatsPrereqs = scorerPrerequisites(
            jobAvecPrereqs.getPrerequisites(), prereqsEvalues
        );

        // ── 4b. Rejet forcé — prérequis obligatoire non satisfait (Contrainte 3) ──
        Optional<PrerequisiteMatchResult> rejetPrereq = resultatsPrereqs.stream()
            .filter(r -> r.isObligatoire() && r.getScoreMatch() < 0.40)
            .findFirst();

        boolean forceRejet    = rejetSkill.isPresent() || rejetPrereq.isPresent();
        String  forceRejetRaison = null;
        if (rejetSkill.isPresent()) {
            forceRejetRaison = "Compétence obligatoire manquante : " + rejetSkill.get().getNom();
        } else if (rejetPrereq.isPresent()) {
            forceRejetRaison = "Prérequis obligatoire non satisfait : "
                + rejetPrereq.get().getType() + " — " + rejetPrereq.get().getRequis();
        }

        // ── 6. Score global ───────────────────────────────────────────────────
        // Partition skills by type for weighted sub-scores
        List<SkillMatchResult> techResults = resultatsSkills.stream()
            .filter(r -> !"SOFT".equalsIgnoreCase(r.getSkillType()))
            .collect(Collectors.toList());
        List<SkillMatchResult> softResults = resultatsSkills.stream()
            .filter(r -> "SOFT".equalsIgnoreCase(r.getSkillType()))
            .collect(Collectors.toList());

        double scoreTech = calculerScoreSkills(techResults);
        double scoreSoft = calculerScoreSkills(softResults);

        int wTech  = jobAvecSkills.getTechnicalSkillWeight() != null ? jobAvecSkills.getTechnicalSkillWeight() : 60;
        int wSoft  = jobAvecSkills.getSoftSkillWeight()      != null ? jobAvecSkills.getSoftSkillWeight()      : 40;

        double scoreSkills;
        if (techResults.isEmpty() && !softResults.isEmpty()) {
            scoreSkills = scoreSoft;
        } else if (softResults.isEmpty() && !techResults.isEmpty()) {
            scoreSkills = scoreTech;
        } else if (!techResults.isEmpty() && !softResults.isEmpty()) {
            scoreSkills = arrondir((scoreTech * wTech + scoreSoft * wSoft) / (double)(wTech + wSoft));
        } else {
            scoreSkills = 0.0;
        }

        double scorePrerequisite = calculerScorePrerequisites(resultatsPrereqs);
        // Poids définis par le RH lors de la création du poste (défaut 70/30)
        double wSkills  = (jobAvecSkills.getSkillsWeight()        != null ? jobAvecSkills.getSkillsWeight()        : 70) / 100.0;
        double wPrereqs = (jobAvecSkills.getPrerequisitesWeight() != null ? jobAvecSkills.getPrerequisitesWeight() : 30) / 100.0;
        double scoreGlobal = forceRejet ? 0.0
            : arrondir(scoreSkills * wSkills + scorePrerequisite * wPrereqs);
        String recommendation = determinerRecommandation(scoreGlobal, forceRejet);

        // ── 7. Sauvegarde BDD (upsert) ────────────────────────────────────────
        MatchingReport rapport = reportRepository
            .findByJobIdAndCandidateId(jobId, candidateId)
            .orElse(MatchingReport.builder().jobId(jobId).candidateId(candidateId).build());

        rapport.setScoreGlobal(scoreGlobal);
        rapport.setRecommendation(recommendation);
        rapport.setForceRejet(forceRejet);
        rapport.setForceRejetRaison(forceRejetRaison);
        rapport.setSkillsJson(json(resultatsSkills));
        rapport.setPrerequisiteJson(json(resultatsPrereqs));
        rapport.setComputedAt(LocalDateTime.now());
        rapport.setCvHash(cvHash);

        MatchingReport saved = reportRepository.save(rapport);
        log.info("✅ Rapport sauvegardé — {}% — {} — {}",
            scoreGlobal, recommendation, candidat.getEmail());

        // ── Sync job_matches (lu par getMatchScores + ApplicationStageProgress) ─
        JobMatch jobMatch = jobMatchRepository
            .findByCandidateIdAndJobId(candidateId, jobId)
            .orElse(JobMatch.builder().candidateId(candidateId).jobId(jobId).build());
        jobMatch.setScore((int) Math.round(scoreGlobal));
        jobMatch.setVerdict(recommendation);
        jobMatch.setCvHash(cvHash);
        jobMatch.setComputedAt(LocalDateTime.now());
        jobMatchRepository.save(jobMatch);

        // ── Construction du DTO de retour ─────────────────────────────────────
        return MatchingReportDTO.builder()
            .id(saved.getId())
            .jobId(jobId)
            .jobTitre(jobAvecSkills.getTitle())
            .candidateId(candidateId)
            .candidatNom(candidat.getFirstName() + " " + candidat.getLastName())
            .scoreGlobal(scoreGlobal)
            .recommendation(recommendation)
            .forceRejet(forceRejet)
            .forceRejetRaison(forceRejetRaison)
            .skills(resultatsSkills)
            .scoreSkills(scoreSkills)
            .scoreSkillsTechnique(arrondir(scoreTech))
            .scoreSkillsSoft(arrondir(scoreSoft))
            .prerequis(resultatsPrereqs)
            .scorePrerequisite(scorePrerequisite)
            .computedAt(saved.getComputedAt())
            .build();
    }

    /**
     * Récupère le dernier rapport en BDD pour un couple job/candidat.
     */
    public Optional<MatchingReportDTO> getReport(String jobId, String candidateId) {
        return reportRepository.findByJobIdAndCandidateId(jobId, candidateId)
            .map(r -> {
                Job job = jobRepository.findByIdWithSkills(jobId).orElse(new Job());
                Candidate c = candidateRepository.findById(candidateId).orElse(new Candidate());
                return deserialiserRapport(r, job, c);
            });
    }

    /**
     * Tous les rapports d'un poste, triés par score décroissant (vue liste RH).
     */
    public List<MatchingReportDTO> getReportsByJob(String jobId) {
        Job job = jobRepository.findByIdWithSkills(jobId).orElse(new Job());
        return reportRepository.findByJobIdOrderByScoreGlobalDesc(jobId).stream()
            .map(r -> {
                Candidate c = candidateRepository.findById(r.getCandidateId()).orElse(new Candidate());
                return deserialiserRapport(r, job, c);
            })
            .collect(Collectors.toList());
    }


    // ═════════════════════════════════════════════════════════════════════════
    // Scoring des compétences
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Construit les SkillMatchResult en utilisant les scores MiniLM calculés par Python.
     * Contrainte 1 : poids vient du job (jamais codé en dur).
     * Contrainte 5 : zéro calcul d'embeddings dans Java — scores viennent de Python.
     */
    private List<SkillMatchResult> scorerCompetences(
            List<TechnicalSkill> skillsPoste,
            List<CvExtractionResult.SkillEvalue> skillsEvalues) {

        List<SkillMatchResult> resultats = new ArrayList<>();

        // Index des scores Python par nom de skill (insensible à la casse)
        Map<String, Double> scoreMap = new java.util.HashMap<>();
        
        log.info("📥 scoreMap reçu de Python : {}", scoreMap);
        log.info("📥 Skills du job à matcher : {}",
            skillsPoste.stream()
                .map(s -> s.getName() + "[" + s.getSkillType() + "]")
                .collect(Collectors.toList())
        );
        if (skillsEvalues != null) {
        	skillsEvalues.forEach(e -> {
        	    if (e.getNom() != null) 
        	        scoreMap.put(e.getNom().trim().toLowerCase(), e.getScore()); // ← trim()
        	});
        }

        for (TechnicalSkill skill : skillsPoste) {
            String  nom         = skill.getName();
            int     poids       = (skill.getWeight() != null && skill.getWeight() > 0) ? skill.getWeight() : 10;
            boolean obligatoire = Boolean.TRUE.equals(skill.getObligatory());

            double score = nom != null ? scoreMap.getOrDefault(nom.trim().toLowerCase(), 0.0) : 0.0;

            resultats.add(SkillMatchResult.builder()
                .nom(nom)
                .poids(poids)
                .obligatoire(obligatoire)
                .similarite(arrondir3(score))
                .statut(statut(score))
                .matchType(statut(score))
                .competenceTrouvee(score >= seuilPartiel ? nom : "")
                .skillType(skill.getSkillType() != null ? skill.getSkillType() : "TECHNICAL")
                .build());
        }

        return resultats;
    }

    private String statut(double sim) {
        if (sim >= seuilMatch)   return "MATCHED";
        if (sim >= seuilPartiel) return "PARTIAL";
        return "MISSING";
    }

    /** Score pondéré des compétences — utilise directement le score MiniLM 0–1 × 100. */
    private double calculerScoreSkills(List<SkillMatchResult> resultats) {
        double poids = 0, total = 0;
        for (SkillMatchResult r : resultats) {
            total += r.getSimilarite() * 100.0 * r.getPoids();
            poids += r.getPoids();
        }
        return poids > 0 ? arrondir(total / poids) : 0.0;
    }


    // ═════════════════════════════════════════════════════════════════════════
    // Scoring des prérequis
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Construit les PrerequisiteMatchResult en utilisant les scores Qwen RAG calculés par Python.
     * Zéro switch/case par type : la sémantique est entièrement dans Python.
     */
    private List<PrerequisiteMatchResult> scorerPrerequisites(
            List<Prerequisite> prereqs,
            List<CvExtractionResult.PrerequisEvalue> prerequisEvalues) {

        List<PrerequisiteMatchResult> resultats = new ArrayList<>();

        for (Prerequisite p : prereqs) {
            String  type        = p.getType() != null ? p.getType().toUpperCase() : "SKILL";
            String  valeur      = p.getValue() != null ? p.getValue() : "";
            boolean obligatoire = Boolean.TRUE.equals(p.getObligatory());
            int     poids       = (p.getWeight() != null && p.getWeight() > 0) ? p.getWeight() : 100;

            // Find Python's evaluation for this prerequisite (match by type + requis)
            Optional<CvExtractionResult.PrerequisEvalue> eval = prerequisEvalues.stream()
                .filter(e -> type.equalsIgnoreCase(e.getType()))
                .filter(e -> e.getRequis() != null && (
                    valeur.equalsIgnoreCase(e.getRequis()) ||
                    e.getRequis().toLowerCase().contains(valeur.toLowerCase()) ||
                    valeur.toLowerCase().contains(e.getRequis().toLowerCase())
                ))
                .findFirst();

            double score   = eval.map(CvExtractionResult.PrerequisEvalue::getScore).orElse(0.0);
            String detecte = eval.map(CvExtractionResult.PrerequisEvalue::getDetecte).orElse("");
            if (detecte == null) detecte = "";

            resultats.add(PrerequisiteMatchResult.builder()
                .type(type)
                .requis(valeur)
                .detecte(detecte)
                .obligatoire(obligatoire)
                .poids(poids)
                .scoreMatch(arrondir3(score))
                .satisfait(score >= 0.40)
                .build());
        }

        return resultats;
    }

    private double calculerScorePrerequisites(List<PrerequisiteMatchResult> resultats) {
        if (resultats.isEmpty()) return 100.0;
        double totalPondere = 0, totalPoids = 0;
        for (PrerequisiteMatchResult r : resultats) {
            int p = r.getPoids() > 0 ? r.getPoids() : 100;
            totalPondere += r.getScoreMatch() * p;
            totalPoids   += p;
        }
        return totalPoids > 0 ? arrondir(totalPondere / totalPoids * 100.0) : 0.0;
    }


    // ═════════════════════════════════════════════════════════════════════════
    // Recommandation finale
    // ═════════════════════════════════════════════════════════════════════════

    private String determinerRecommandation(double scoreGlobal, boolean forceRejet) {
        if (forceRejet || scoreGlobal < seuilEtudier)  return "REJETER";
        if (scoreGlobal >= seuilRetenir)                return "RETENIR";
        return "A_ETUDIER";
    }


    // ═════════════════════════════════════════════════════════════════════════
    // Lecture CV depuis le disque (candidat sans upload à la volée)
    // ═════════════════════════════════════════════════════════════════════════

    private byte[] lireCvDuDisque(Candidate candidat) {
        try {
            String cvPath = candidat.getCvPath();
            if (cvPath == null || cvPath.isBlank()) return null;
            // Format stocké : "NomOriginal.pdf|nomFichier.pdf"
            String fileName = cvPath.contains("|") ? cvPath.split("\\|")[1] : cvPath;
            java.io.File file = Paths.get(uploadDir).toAbsolutePath().resolve(fileName).toFile();
            if (!file.exists()) {
                log.warn("CV introuvable sur disque : {}", file.getAbsolutePath());
                return null;
            }
            return Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            log.error("Erreur lecture CV disque : {}", e.getMessage());
            return null;
        }
    }

    private String nomFichierCv(Candidate candidat) {
        String cvPath = candidat.getCvPath();
        if (cvPath == null) return "cv.pdf";
        return cvPath.contains("|") ? cvPath.split("\\|")[0] : cvPath;
    }


    // ═════════════════════════════════════════════════════════════════════════
    // Utilitaires mathématiques et JSON
    // ═════════════════════════════════════════════════════════════════════════

    private double arrondir(double v)  { return Math.round(v * 10.0)    / 10.0; }
    private double arrondir3(double v) { return Math.round(v * 1000.0)  / 1000.0; }

    private String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            return String.valueOf(Arrays.hashCode(data));
        }
    }

    private String json(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return "[]"; }
    }

    private MatchingReportDTO deserialiserRapport(MatchingReport r, Job job, Candidate c) {
        try {
            List<SkillMatchResult> skills = objectMapper.readValue(
                r.getSkillsJson() != null ? r.getSkillsJson() : "[]",
                new TypeReference<>() {});
            List<PrerequisiteMatchResult> prereqs = objectMapper.readValue(
                r.getPrerequisiteJson() != null ? r.getPrerequisiteJson() : "[]",
                new TypeReference<>() {});

            // Recalcul des sous-scores tech/soft depuis les données stockées
            List<SkillMatchResult> techSkills = skills.stream()
                .filter(s -> !"SOFT".equalsIgnoreCase(s.getSkillType()))
                .collect(Collectors.toList());
            List<SkillMatchResult> softSkills = skills.stream()
                .filter(s -> "SOFT".equalsIgnoreCase(s.getSkillType()))
                .collect(Collectors.toList());

            return MatchingReportDTO.builder()
                .id(r.getId())
                .jobId(r.getJobId())
                .jobTitre(job.getTitle())
                .candidateId(r.getCandidateId())
                .candidatNom(c.getFirstName() + " " + c.getLastName())
                .scoreGlobal(r.getScoreGlobal())
                .recommendation(r.getRecommendation())
                .forceRejet(r.isForceRejet())
                .forceRejetRaison(r.getForceRejetRaison())
                .skills(skills)
                .scoreSkills(calculerScoreSkills(skills))
                .scoreSkillsTechnique(arrondir(calculerScoreSkills(techSkills)))
                .scoreSkillsSoft(arrondir(calculerScoreSkills(softSkills)))
                .prerequis(prereqs)
                .scorePrerequisite(calculerScorePrerequisites(prereqs))
                .computedAt(r.getComputedAt())
                .build();
        } catch (Exception e) {
            log.error("Erreur désérialisation rapport {} : {}", r.getId(), e.getMessage());
            throw new RuntimeException("Erreur chargement rapport : " + e.getMessage());
        }
    }
}
