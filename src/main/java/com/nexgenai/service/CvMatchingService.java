package com.nexgenai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexgenai.dto.matching.*;
import com.nexgenai.model.*;
import com.nexgenai.repository.CandidateRepository;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Orchestrateur du matching CV ↔ Offre (nouvelle architecture Python + Java).
 *
 * Workflow :
 *  1. Appel Python /extract → embeddings CV (JobBERTa)
 *  2. Appel Python /embed   → embeddings skills du poste (JobBERTa)
 *  3. Similarité cosinus pour chaque skill requis
 *  4. Application règles de rejet forcé (obligatory + seuils)
 *  5. Score global pondéré (weights viennent du job créé par le RH)
 *  6. Analyse Mistral (points forts / faibles)
 *  7. Sauvegarde BDD (upsert par jobId + candidateId)
 *
 * Contrainte 1 : les weights viennent TOUJOURS du job — jamais codés en dur.
 * Contrainte 2 : skill obligatoire + similarité < 0.50 → REJETER forcé.
 * Contrainte 3 : prérequis obligatoire + match < 0.40 → REJETER forcé.
 * Contrainte 4 : Python indisponible → RuntimeException message clair.
 * Contrainte 5 : seule la similarité cosinus est calculée ici (Java).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CvMatchingService {

    private final PythonExtractorClient   pythonClient;
    private final OllamaMatchingService   ollamaService;   // Mistral (existant)
    private final JobRepository           jobRepository;
    private final MatchingReportRepository reportRepository;
    private final CandidateRepository     candidateRepository;
    private final ObjectMapper            objectMapper;

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

        // ── Chargement du poste (deux requêtes pour éviter MultipleBagFetchException) ──
        Job jobAvecSkills = jobRepository.findByIdWithSkills(jobId)
            .orElseThrow(() -> new RuntimeException("Poste introuvable : " + jobId));
        Job jobAvecPrereqs = jobRepository.findByIdWithPrerequisites(jobId)
            .orElseThrow(() -> new RuntimeException("Poste introuvable : " + jobId));

        log.info("🎯 Matching : {} ↔ {}", candidat.getEmail(), jobAvecSkills.getTitle());

        // ── 1. Extraction CV via Python ────────────────────────────────────────
        CvExtractionResult extraction = pythonClient.extraireCv(cvBytes, cvFilename);
        log.info("📄 {} compétences extraites du CV", extraction.getCompetences().size());

        // ── Cache : même CV + même poste → rapport existant ──────────────────
        String cvHash = md5(cvBytes);
        Optional<MatchingReport> cached = reportRepository
            .findByJobIdAndCandidateIdAndCvHash(jobId, candidateId, cvHash);
        if (cached.isPresent()) {
            log.info("⚡ Rapport en cache retourné (CV inchangé)");
            return deserialiserRapport(cached.get(), jobAvecSkills, candidat);
        }

        // ── 2. Embeddings des skills du poste via Python ──────────────────────
        // Contrainte 1 : les skills viennent du job créé par le RH
        List<String> nomsSkills = jobAvecSkills.getTechnicalSkills().stream()
            .map(TechnicalSkill::getName)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        Map<String, List<Double>> embeddingsPoste = Collections.emptyMap();
        if (!nomsSkills.isEmpty()) {
            EmbedResult embedResult = pythonClient.calculerEmbeddings(nomsSkills);
            embeddingsPoste = embedResult.getEmbeddings() != null
                ? embedResult.getEmbeddings() : Collections.emptyMap();
        }

        // ── 3. Scoring des compétences ────────────────────────────────────────
        List<SkillMatchResult> resultatsSkills = scorerCompetences(
            jobAvecSkills.getTechnicalSkills(),
            extraction.getCompetences(),
            embeddingsPoste
        );

        // ── 4a. Rejet forcé — skill obligatoire non satisfait (Contrainte 2) ──
        Optional<SkillMatchResult> rejetSkill = resultatsSkills.stream()
            .filter(r -> r.isObligatoire() && r.getSimilarite() < seuilPartiel)
            .findFirst();

        // ── 5. Scoring des prérequis ──────────────────────────────────────────
        List<PrerequisiteMatchResult> resultatsPrereqs = scorerPrerequisites(
            jobAvecPrereqs.getPrerequisites(),
            extraction
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
        double scoreSkills       = calculerScoreSkills(resultatsSkills);
        double scorePrerequisite = calculerScorePrerequisites(resultatsPrereqs);
        // Poids définis par le RH lors de la création du poste (défaut 70/30)
        double wSkills  = (jobAvecSkills.getSkillsWeight()        != null ? jobAvecSkills.getSkillsWeight()        : 70) / 100.0;
        double wPrereqs = (jobAvecSkills.getPrerequisitesWeight() != null ? jobAvecSkills.getPrerequisitesWeight() : 30) / 100.0;
        double scoreGlobal = forceRejet ? 0.0
            : arrondir(scoreSkills * wSkills + scorePrerequisite * wPrereqs);
        String recommendation = determinerRecommandation(scoreGlobal, forceRejet);

        // ── 7. Analyse Mistral ────────────────────────────────────────────────
        List<String> pointsForts   = new ArrayList<>();
        List<String> pointsFaibles = new ArrayList<>();
        String       analyseTexte  = "";
        try {
            MistralAnalysis analyse = analyserAvecMistral(
                extraction.getTexteBrut(), jobAvecSkills, resultatsSkills, resultatsPrereqs
            );
            pointsForts   = analyse.forts();
            pointsFaibles = analyse.faibles();
            analyseTexte  = analyse.texte();
        } catch (Exception e) {
            log.warn("⚠️ Mistral indisponible — analyse ignorée : {}", e.getMessage());
        }

        // ── 8. Sauvegarde BDD (upsert) ────────────────────────────────────────
        MatchingReport rapport = reportRepository
            .findByJobIdAndCandidateId(jobId, candidateId)
            .orElse(MatchingReport.builder().jobId(jobId).candidateId(candidateId).build());

        rapport.setScoreGlobal(scoreGlobal);
        rapport.setRecommendation(recommendation);
        rapport.setForceRejet(forceRejet);
        rapport.setForceRejetRaison(forceRejetRaison);
        rapport.setSkillsJson(json(resultatsSkills));
        rapport.setPrerequisiteJson(json(resultatsPrereqs));
        rapport.setPointsForts(json(pointsForts));
        rapport.setPointsFaibles(json(pointsFaibles));
        rapport.setAnalyseTexte(analyseTexte);
        rapport.setComputedAt(LocalDateTime.now());
        rapport.setCvHash(cvHash);

        MatchingReport saved = reportRepository.save(rapport);
        log.info("✅ Rapport sauvegardé — {}% — {} — {}",
            scoreGlobal, recommendation, candidat.getEmail());

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
            .prerequis(resultatsPrereqs)
            .scorePrerequisite(scorePrerequisite)
            .pointsForts(pointsForts)
            .pointsFaibles(pointsFaibles)
            .analyseTexte(analyseTexte)
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
     * Pour chaque compétence requise par le poste, calcule la similarité cosinus
     * avec les compétences du CV.
     *
     * Contrainte 1 : poids vient du job (jamais codé en dur).
     * Contrainte 5 : seule la similarité cosinus est calculée ici.
     */
    private List<SkillMatchResult> scorerCompetences(
            List<TechnicalSkill> skillsPoste,
            List<CvExtractionResult.CompetenceExtraite> competencesCv,
            Map<String, List<Double>> embeddingsPoste) {

        List<SkillMatchResult> resultats = new ArrayList<>();

        for (TechnicalSkill skill : skillsPoste) {
            String  nom        = skill.getName();
            // Contrainte 1 : poids défini par le RH, fallback 10 si null
            int     poids      = (skill.getWeight() != null && skill.getWeight() > 0)
                                 ? skill.getWeight() : 10;
            boolean obligatoire = Boolean.TRUE.equals(skill.getObligatory());

            List<Double> embPoste = embeddingsPoste.get(nom);

            double maxSim       = 0.0;
            String meilleureComp = "";

            if (embPoste != null && !competencesCv.isEmpty()) {
                // Calcul cosinus entre l'embedding du skill requis et chaque compétence du CV
                for (CvExtractionResult.CompetenceExtraite comp : competencesCv) {
                    if (comp.getEmbedding() != null && !comp.getEmbedding().isEmpty()) {
                        double sim = cosinus(embPoste, comp.getEmbedding());
                        if (sim > maxSim) {
                            maxSim = sim;
                            meilleureComp = comp.getNom();
                        }
                    }
                }
            } else {
                // Fallback mot-clé si embeddings indisponibles
                boolean kw = competencesCv.stream().anyMatch(c ->
                    c.getNom() != null && (
                        c.getNom().toLowerCase().contains(nom.toLowerCase()) ||
                        nom.toLowerCase().contains(c.getNom().toLowerCase())
                    )
                );
                maxSim       = kw ? 0.80 : 0.0;
                meilleureComp = kw ? nom : "";
            }

            resultats.add(SkillMatchResult.builder()
                .nom(nom)
                .poids(poids)
                .obligatoire(obligatoire)
                .similarite(arrondir3(maxSim))
                .statut(statut(maxSim))
                .competenceTrouvee(meilleureComp)
                .build());
        }

        return resultats;
    }

    private String statut(double sim) {
        if (sim >= seuilMatch)   return "MATCHED";
        if (sim >= seuilPartiel) return "PARTIAL";
        return "MISSING";
    }

    /**
     * Score pondéré des compétences.
     * Contrainte 1 : utilise les poids définis par le RH.
     */
    private double calculerScoreSkills(List<SkillMatchResult> resultats) {
        double poids = 0, total = 0;
        for (SkillMatchResult r : resultats) {
            double contribution = switch (r.getStatut()) {
                case "MATCHED"  -> 100.0;
                case "PARTIAL"  -> 50.0;
                default         -> 0.0;
            };
            total += contribution * r.getPoids();
            poids += r.getPoids();
        }
        return poids > 0 ? arrondir(total / poids) : 0.0;
    }


    // ═════════════════════════════════════════════════════════════════════════
    // Scoring des prérequis
    // ═════════════════════════════════════════════════════════════════════════

    private List<PrerequisiteMatchResult> scorerPrerequisites(
            List<Prerequisite> prereqs,
            CvExtractionResult extraction) {

        List<PrerequisiteMatchResult> resultats = new ArrayList<>();

        for (Prerequisite p : prereqs) {
            String  type        = p.getType() != null ? p.getType().toUpperCase() : "SKILL";
            String  valeur      = p.getValue() != null ? p.getValue() : "";
            boolean obligatoire = Boolean.TRUE.equals(p.getObligatory());
            int     poids       = (p.getWeight() != null && p.getWeight() > 0) ? p.getWeight() : 100;

            double  score   = 0.0;
            String  detecte = "";

            switch (type) {
                case "DEGREE" -> {
                    String niveauRequisLow = valeur.toLowerCase();
                    boolean exact = extraction.getDiplomes().stream().anyMatch(d ->
                        d.getNiveau() != null && (
                            d.getNiveau().toLowerCase().contains(niveauRequisLow) ||
                            niveauRequisLow.contains(d.getNiveau().toLowerCase())
                        )
                    );
                    if (exact) {
                        detecte = extraction.getDiplomes().isEmpty() ? valeur
                            : extraction.getDiplomes().get(0).getNiveau();
                        score = 1.0;
                    } else if (!extraction.getDiplomes().isEmpty()) {
                        // Diplôme inférieur → score partiel
                        detecte = extraction.getDiplomes().get(0).getNiveau();
                        score   = 0.5;
                    }
                }
                case "EXPERIENCE" -> {
                    int moisRequis = parseMoisExperience(valeur);
                    int moisCv     = extraction.getExperienceMois();
                    detecte = (moisCv / 12) + " an(s)";
                    if      (moisCv >= moisRequis)          score = 1.0;
                    else if (moisCv >= moisRequis * 0.70)   score = 0.6;
                    else if (moisCv >= moisRequis * 0.50)   score = 0.3;
                    else                                     score = 0.0;
                }
                case "LANGUAGE" -> {
                    String langLow = valeur.toLowerCase();
                    boolean trouve = extraction.getLangues().stream().anyMatch(l ->
                        l.getLangue() != null && l.getLangue().toLowerCase().contains(langLow)
                    );
                    if (trouve) { detecte = valeur; score = 1.0; }
                }
                case "CERTIFICATION" -> {
                    String certLow = valeur.toLowerCase();
                    boolean trouve = extraction.getCertifications().stream()
                        .anyMatch(c -> c.toLowerCase().contains(certLow));
                    if (trouve) { detecte = valeur; score = 1.0; }
                }
                default -> score = 0.5; // type inconnu → neutre
            }

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

    /** Parse "3 ans", "36 mois", "2 years" → nombre de mois. */
    private int parseMoisExperience(String valeur) {
        if (valeur == null || valeur.isBlank()) return 0;
        Matcher m = Pattern.compile("(\\d+)\\s*(ans?|years?|mois|months?)", Pattern.CASE_INSENSITIVE)
            .matcher(valeur);
        if (m.find()) {
            int nb    = Integer.parseInt(m.group(1));
            String u  = m.group(2).toLowerCase();
            return u.startsWith("mo") ? nb : nb * 12;
        }
        return 0;
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
    // Analyse Mistral (réutilise OllamaMatchingService existant)
    // ═════════════════════════════════════════════════════════════════════════

    private record MistralAnalysis(List<String> forts, List<String> faibles, String texte) {}

    private MistralAnalysis analyserAvecMistral(
            String texteCv,
            Job job,
            List<SkillMatchResult> skills,
            List<PrerequisiteMatchResult> prereqs) {

        // Construction d'un résumé structuré pour Mistral
        StringBuilder offerText = new StringBuilder();
        offerText.append("Poste : ").append(job.getTitle()).append("\n");
        offerText.append("Compétences trouvées : ");
        skills.stream().filter(s -> "MATCHED".equals(s.getStatut()))
            .forEach(s -> offerText.append(s.getNom()).append(", "));
        offerText.append("\nCompétences manquantes : ");
        skills.stream().filter(s -> "MISSING".equals(s.getStatut()))
            .forEach(s -> offerText.append(s.getNom()).append(", "));
        offerText.append("\nDemande une analyse courte des points forts et lacunes.");

        String cvResume = texteCv != null
            ? texteCv.substring(0, Math.min(texteCv.length(), 1200))
            : "";

        OllamaMatchingService.MatchResult result = ollamaService.analyze(cvResume, offerText.toString());

        // Extraction des points depuis le résumé Mistral
        List<String> forts   = new ArrayList<>();
        List<String> faibles = new ArrayList<>();
        String       resume  = result.getResume() != null ? result.getResume() : "";

        // Points forts = compétences maîtrisées
        skills.stream()
            .filter(s -> "MATCHED".equals(s.getStatut()))
            .limit(5)
            .forEach(s -> forts.add("Maîtrise confirmée : " + s.getNom()));
        if (!resume.isBlank()) forts.add(resume);

        // Points faibles = compétences manquantes
        skills.stream()
            .filter(s -> "MISSING".equals(s.getStatut()) && s.isObligatoire())
            .limit(3)
            .forEach(s -> faibles.add("Compétence obligatoire absente : " + s.getNom()));
        skills.stream()
            .filter(s -> "MISSING".equals(s.getStatut()) && !s.isObligatoire())
            .limit(3)
            .forEach(s -> faibles.add("Compétence manquante : " + s.getNom()));

        return new MistralAnalysis(forts, faibles, resume);
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

    /**
     * Similarité cosinus entre deux vecteurs d'embeddings.
     * C'est le SEUL calcul mathématique que Spring Boot fait — les embeddings
     * eux-mêmes viennent de Python (Contrainte 5).
     */
    private double cosinus(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.size() != b.size()) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.size(); i++) {
            dot   += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

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
            List<String> forts = objectMapper.readValue(
                r.getPointsForts() != null ? r.getPointsForts() : "[]",
                new TypeReference<>() {});
            List<String> faibles = objectMapper.readValue(
                r.getPointsFaibles() != null ? r.getPointsFaibles() : "[]",
                new TypeReference<>() {});

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
                .prerequis(prereqs)
                .scorePrerequisite(calculerScorePrerequisites(prereqs))
                .pointsForts(forts)
                .pointsFaibles(faibles)
                .analyseTexte(r.getAnalyseTexte())
                .computedAt(r.getComputedAt())
                .build();
        } catch (Exception e) {
            log.error("Erreur désérialisation rapport {} : {}", r.getId(), e.getMessage());
            throw new RuntimeException("Erreur chargement rapport : " + e.getMessage());
        }
    }
}
