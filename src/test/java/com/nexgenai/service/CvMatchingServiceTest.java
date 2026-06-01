package com.nexgenai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexgenai.dto.matching.CvExtractionResult;
import com.nexgenai.model.*;
import com.nexgenai.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires â€” CvMatchingService (matching CV â†” Offre)
 *
 * ScÃ©narios couverts :
 *   TC-SC-01  Python indisponible                    â†’ RuntimeException message explicite
 *   TC-SC-02  CV non uploadÃ© (cvPath null)           â†’ RuntimeException "Aucun CV disponible"
 *   TC-SC-03  Candidat introuvable                   â†’ RuntimeException
 *   TC-SC-04  Cache hit (mÃªme cvHash)                â†’ rapport retournÃ© sans appel Python
 *   TC-SC-05  Skill obligatoire manquant (sim < 0.50)â†’ forceRejet=true, scoreGlobal=0
 *   TC-SC-06  PrÃ©requis obligatoire score < 0.40     â†’ forceRejet=true, scoreGlobal=0
 *   TC-SC-07  Tous les skills matchent parfaitement  â†’ scoreGlobal > 0, RETENIR
 *   TC-SC-08  Score global â‰¥ 75                      â†’ recommendation = RETENIR
 *   TC-SC-09  Score global 50â€“74                     â†’ recommendation = A_ETUDIER
 *   TC-SC-10  Score global < 50                      â†’ recommendation = REJETER
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CvMatchingService â€” Tests Unitaires")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CvMatchingServiceTest {

    @Mock private PythonExtractorClient    pythonClient;
    @Mock private JobRepository            jobRepository;
    @Mock private MatchingReportRepository reportRepository;
    @Mock private JobMatchRepository       jobMatchRepository;
    @Mock private CandidateRepository      candidateRepository;

    @InjectMocks
    private CvMatchingService svc;

    private static final String JOB_ID       = "job-001";
    private static final String CANDIDATE_ID = "cand-001";
    private static final byte[] CV_BYTES     = "dummy-cv-content".getBytes();
    private static final String CV_FILENAME  = "cv.pdf";

    @BeforeEach
    void injectThresholds() {
        // @Value fields not populated by @InjectMocks â€” inject manually
        ReflectionTestUtils.setField(svc, "seuilMatch",    0.75);
        ReflectionTestUtils.setField(svc, "seuilPartiel",  0.50);
        ReflectionTestUtils.setField(svc, "seuilRetenir",  75.0);
        ReflectionTestUtils.setField(svc, "seuilEtudier",  50.0);
        ReflectionTestUtils.setField(svc, "uploadDir",     "uploads/cv");
        ReflectionTestUtils.setField(svc, "objectMapper",  new ObjectMapper());
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private Candidate makeCandidate() {
        Candidate c = new Candidate();
        c.setFirstName("Alice");
        c.setLastName("Martin");
        c.setEmail("alice@example.com");
        c.setCvPath(null);   // no CV stored by default
        return c;
    }

    private Job makeJob(List<TechnicalSkill> skills, List<Prerequisite> prereqs) {
        Job job = new Job();
        job.setId(JOB_ID);
        job.setTitle("Backend Dev");
        job.setTechnicalSkills(skills);
        job.setPrerequisites(prereqs);
        job.setSkillsWeight(70);
        job.setPrerequisitesWeight(30);
        job.setTechnicalSkillWeight(60);
        job.setSoftSkillWeight(40);
        return job;
    }

    private TechnicalSkill makeSkill(String name, boolean obligatory) {
        TechnicalSkill s = new TechnicalSkill();
        s.setName(name);
        s.setObligatory(obligatory);
        s.setWeight(10);
        s.setSkillType("TECHNICAL");
        return s;
    }

    private CvExtractionResult makeExtraction(List<CvExtractionResult.SkillEvalue> skills,
                                              List<CvExtractionResult.PrerequisEvalue> prereqs) {
        CvExtractionResult result = new CvExtractionResult();
        result.setSkillsEvalues(skills);
        result.setPrerequisEvalues(prereqs);
        return result;
    }

    private CvExtractionResult.SkillEvalue makeSkillEvalue(String nom, double score) {
        CvExtractionResult.SkillEvalue e = new CvExtractionResult.SkillEvalue();
        e.setNom(nom);
        e.setScore(score);
        e.setType("TECHNICAL");
        e.setPresent(score >= 0.55);
        return e;
    }

    private MatchingReport makeSavedReport(double score, String recommendation) {
        MatchingReport r = new MatchingReport();
        r.setId("report-001");
        r.setJobId(JOB_ID);
        r.setCandidateId(CANDIDATE_ID);
        r.setScoreGlobal(score);
        r.setRecommendation(recommendation);
        r.setForceRejet(false);
        r.setSkillsJson("[]");
        r.setPrerequisiteJson("[]");
        r.setComputedAt(LocalDateTime.now());
        r.setCvHash("abc123");
        return r;
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // TC-SC-01 â€” Python indisponible
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    @Order(1)
    @DisplayName("TC-SC-01 : Python indisponible â†’ RuntimeException avec message explicite")
    void lancerMatching_pythonUnavailable_throwsWithMessage() {
        // Given
        when(pythonClient.isAvailable()).thenReturn(false);

        // When / Then
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> svc.lancerMatching(JOB_ID, CANDIDATE_ID, CV_BYTES, CV_FILENAME));

        assertTrue(ex.getMessage().toLowerCase().contains("python") ||
                   ex.getMessage().toLowerCase().contains("microservice") ||
                   ex.getMessage().toLowerCase().contains("indisponible"),
            "Message should mention Python unavailability, got: " + ex.getMessage());
        verify(candidateRepository, never()).findById(any());
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // TC-SC-02 â€” CV non uploadÃ©
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    @Order(2)
    @DisplayName("TC-SC-02 : CV non uploadÃ© (cvPath null + cvBytes null) â†’ RuntimeException 'Aucun CV disponible'")
    void lancerMatching_noCvStored_throwsNoCvAvailable() {
        // Given
        when(pythonClient.isAvailable()).thenReturn(true);
        when(candidateRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(makeCandidate()));

        // When / Then â€” pass null bytes; candidate has no cvPath
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> svc.lancerMatching(JOB_ID, CANDIDATE_ID, null, CV_FILENAME));

        assertTrue(ex.getMessage().toLowerCase().contains("cv") ||
                   ex.getMessage().toLowerCase().contains("disponible"),
            "Message should mention missing CV, got: " + ex.getMessage());
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // TC-SC-03 â€” Candidat introuvable
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    @Order(3)
    @DisplayName("TC-SC-03 : Candidat introuvable â†’ RuntimeException")
    void lancerMatching_candidateNotFound_throwsRuntimeException() {
        // Given
        when(pythonClient.isAvailable()).thenReturn(true);
        when(candidateRepository.findById(CANDIDATE_ID)).thenReturn(Optional.empty());

        // When / Then
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> svc.lancerMatching(JOB_ID, CANDIDATE_ID, CV_BYTES, CV_FILENAME));

        assertTrue(ex.getMessage().toLowerCase().contains("candidat") ||
                   ex.getMessage().toLowerCase().contains("introuvable"),
            "Message should mention candidate not found, got: " + ex.getMessage());
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // TC-SC-04 â€” Cache hit
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    @Order(4)
    @DisplayName("TC-SC-04 : Cache hit (mÃªme cvHash) â†’ rapport retournÃ© sans appel Python")
    void lancerMatching_cacheHit_returnsCachedReportWithoutCallingPython() {
        // Given
        when(pythonClient.isAvailable()).thenReturn(true);
        when(candidateRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(makeCandidate()));

        Job job = makeJob(Collections.emptyList(), Collections.emptyList());
        when(jobRepository.findByIdWithSkills(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.findByIdWithPrerequisites(JOB_ID)).thenReturn(Optional.of(job));

        MatchingReport cached = makeSavedReport(85.0, "RETENIR");
        when(reportRepository.findByJobIdAndCandidateIdAndCvHash(
                eq(JOB_ID), eq(CANDIDATE_ID), anyString()))
            .thenReturn(Optional.of(cached));
        when(jobMatchRepository.existsByCandidateIdAndJobIdAndCvHash(
                eq(CANDIDATE_ID), eq(JOB_ID), anyString()))
            .thenReturn(true);

        // When
        var dto = svc.lancerMatching(JOB_ID, CANDIDATE_ID, CV_BYTES, CV_FILENAME);

        // Then
        assertNotNull(dto);
        assertEquals(85.0, dto.getScoreGlobal());
        assertEquals("RETENIR", dto.getRecommendation());
        // Python must NOT have been called
        verify(pythonClient, never()).extraireCv(any(), any(), any(), any(), any());
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // TC-SC-05 â€” Skill obligatoire manquant â†’ rejet forcÃ©
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    @Order(5)
    @DisplayName("TC-SC-05 : Skill obligatoire similarity < 0.50 â†’ forceRejet=true, scoreGlobal=0")
    void lancerMatching_missingMandatorySkill_forcesRejection() throws Exception {
        // Given
        when(pythonClient.isAvailable()).thenReturn(true);
        when(candidateRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(makeCandidate()));

        TechnicalSkill mandatorySkill = makeSkill("Java", true);
        Job job = makeJob(List.of(mandatorySkill), Collections.emptyList());
        when(jobRepository.findByIdWithSkills(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.findByIdWithPrerequisites(JOB_ID)).thenReturn(Optional.of(job));

        // No cache
        when(reportRepository.findByJobIdAndCandidateIdAndCvHash(any(), any(), any()))
            .thenReturn(Optional.empty());

        // Python returns score 0.10 for Java (below seuilPartiel=0.50)
        CvExtractionResult extraction = makeExtraction(
            List.of(makeSkillEvalue("Java", 0.10)),
            Collections.emptyList()
        );
        when(pythonClient.extraireCv(any(), any(), any(), any(), any())).thenReturn(extraction);

        // Save/find mocks
        when(reportRepository.findByJobIdAndCandidateId(JOB_ID, CANDIDATE_ID))
            .thenReturn(Optional.empty());
        MatchingReport saved = makeSavedReport(0.0, "REJETER");
        saved.setForceRejet(true);
        when(reportRepository.save(any())).thenReturn(saved);
        when(jobMatchRepository.findByCandidateIdAndJobId(CANDIDATE_ID, JOB_ID))
            .thenReturn(Optional.empty());
        when(jobMatchRepository.save(any())).thenReturn(new JobMatch());

        // When
        var dto = svc.lancerMatching(JOB_ID, CANDIDATE_ID, CV_BYTES, CV_FILENAME);

        // Then
        assertTrue(dto.isForceRejet(), "forceRejet must be true for missing mandatory skill");
        assertEquals(0.0, dto.getScoreGlobal(), 0.001);
        assertEquals("REJETER", dto.getRecommendation());
        assertNotNull(dto.getForceRejetRaison());
        assertTrue(dto.getForceRejetRaison().toLowerCase().contains("java") ||
                   dto.getForceRejetRaison().toLowerCase().contains("obligatoire"),
            "Reason should mention missing skill, got: " + dto.getForceRejetRaison());
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // TC-SC-06 â€” PrÃ©requis obligatoire non satisfait â†’ rejet forcÃ©
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    @Order(6)
    @DisplayName("TC-SC-06 : PrÃ©requis obligatoire score < 0.40 â†’ forceRejet=true, scoreGlobal=0")
    void lancerMatching_missingMandatoryPrereq_forcesRejection() throws Exception {
        // Given
        when(pythonClient.isAvailable()).thenReturn(true);
        when(candidateRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(makeCandidate()));

        Prerequisite mandatoryPrereq = new Prerequisite();
        mandatoryPrereq.setType("DEGREE");
        mandatoryPrereq.setValue("Bac+5 Informatique");
        mandatoryPrereq.setObligatory(true);
        mandatoryPrereq.setWeight(100);

        Job jobSkills  = makeJob(Collections.emptyList(), Collections.emptyList());
        Job jobPrereqs = makeJob(Collections.emptyList(), List.of(mandatoryPrereq));
        when(jobRepository.findByIdWithSkills(JOB_ID)).thenReturn(Optional.of(jobSkills));
        when(jobRepository.findByIdWithPrerequisites(JOB_ID)).thenReturn(Optional.of(jobPrereqs));

        when(reportRepository.findByJobIdAndCandidateIdAndCvHash(any(), any(), any()))
            .thenReturn(Optional.empty());

        // Python returns prereq score 0.20 (below 0.40 threshold)
        CvExtractionResult.PrerequisEvalue prereqEval = new CvExtractionResult.PrerequisEvalue();
        prereqEval.setType("DEGREE");
        prereqEval.setRequis("Bac+5 Informatique");
        prereqEval.setDetecte("BTS Informatique");
        prereqEval.setScore(0.20);
        prereqEval.setPresent(false);

        CvExtractionResult extraction = makeExtraction(Collections.emptyList(), List.of(prereqEval));
        when(pythonClient.extraireCv(any(), any(), any(), any(), any())).thenReturn(extraction);

        when(reportRepository.findByJobIdAndCandidateId(JOB_ID, CANDIDATE_ID))
            .thenReturn(Optional.empty());
        MatchingReport saved = makeSavedReport(0.0, "REJETER");
        saved.setForceRejet(true);
        when(reportRepository.save(any())).thenReturn(saved);
        when(jobMatchRepository.findByCandidateIdAndJobId(CANDIDATE_ID, JOB_ID))
            .thenReturn(Optional.empty());
        when(jobMatchRepository.save(any())).thenReturn(new JobMatch());

        // When
        var dto = svc.lancerMatching(JOB_ID, CANDIDATE_ID, CV_BYTES, CV_FILENAME);

        // Then
        assertTrue(dto.isForceRejet());
        assertEquals(0.0, dto.getScoreGlobal(), 0.001);
        assertEquals("REJETER", dto.getRecommendation());
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // TC-SC-07 â€” Score = 100% (profil parfait)
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    @Order(7)
    @DisplayName("TC-SC-07 : Tous les skills Ã  1.0 â†’ scoreGlobal = 100, recommendation = RETENIR")
    void lancerMatching_perfectMatch_score100AndRetenir() throws Exception {
        // Given
        when(pythonClient.isAvailable()).thenReturn(true);
        when(candidateRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(makeCandidate()));

        TechnicalSkill s1 = makeSkill("Java",       false);
        TechnicalSkill s2 = makeSkill("Spring Boot", false);
        Job job = makeJob(List.of(s1, s2), Collections.emptyList());
        when(jobRepository.findByIdWithSkills(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.findByIdWithPrerequisites(JOB_ID)).thenReturn(Optional.of(job));

        when(reportRepository.findByJobIdAndCandidateIdAndCvHash(any(), any(), any()))
            .thenReturn(Optional.empty());

        CvExtractionResult extraction = makeExtraction(
            List.of(
                makeSkillEvalue("Java",        1.0),
                makeSkillEvalue("Spring Boot", 1.0)
            ),
            Collections.emptyList()
        );
        when(pythonClient.extraireCv(any(), any(), any(), any(), any())).thenReturn(extraction);

        when(reportRepository.findByJobIdAndCandidateId(JOB_ID, CANDIDATE_ID))
            .thenReturn(Optional.empty());
        MatchingReport saved = makeSavedReport(100.0, "RETENIR");
        when(reportRepository.save(any())).thenReturn(saved);
        when(jobMatchRepository.findByCandidateIdAndJobId(CANDIDATE_ID, JOB_ID))
            .thenReturn(Optional.empty());
        when(jobMatchRepository.save(any())).thenReturn(new JobMatch());

        // When
        var dto = svc.lancerMatching(JOB_ID, CANDIDATE_ID, CV_BYTES, CV_FILENAME);

        // Then
        assertFalse(dto.isForceRejet());
        assertEquals(100.0, dto.getScoreGlobal(), 0.1);
        assertEquals("RETENIR", dto.getRecommendation());
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // TC-SC-08 â€” Recommandation RETENIR (score â‰¥ 75)
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    @Order(8)
    @DisplayName("TC-SC-08 : scoreGlobal â‰¥ 75 â†’ recommendation = RETENIR")
    void lancerMatching_scoreAbove75_recommendsRetenir() throws Exception {
        // Given â€” skills match at 0.80 (score = 80%)
        when(pythonClient.isAvailable()).thenReturn(true);
        when(candidateRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(makeCandidate()));

        TechnicalSkill skill = makeSkill("Python", false);
        Job job = makeJob(List.of(skill), Collections.emptyList());
        when(jobRepository.findByIdWithSkills(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.findByIdWithPrerequisites(JOB_ID)).thenReturn(Optional.of(job));

        when(reportRepository.findByJobIdAndCandidateIdAndCvHash(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(pythonClient.extraireCv(any(), any(), any(), any(), any()))
            .thenReturn(makeExtraction(List.of(makeSkillEvalue("Python", 0.80)), Collections.emptyList()));

        when(reportRepository.findByJobIdAndCandidateId(JOB_ID, CANDIDATE_ID))
            .thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenReturn(makeSavedReport(80.0, "RETENIR"));
        when(jobMatchRepository.findByCandidateIdAndJobId(CANDIDATE_ID, JOB_ID))
            .thenReturn(Optional.empty());
        when(jobMatchRepository.save(any())).thenReturn(new JobMatch());

        // When
        var dto = svc.lancerMatching(JOB_ID, CANDIDATE_ID, CV_BYTES, CV_FILENAME);

        // Then
        assertEquals("RETENIR", dto.getRecommendation());
        assertTrue(dto.getScoreGlobal() >= 75.0);
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // TC-SC-09 â€” Recommandation A_ETUDIER (score 50â€“74)
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    @Order(9)
    @DisplayName("TC-SC-09 : scoreGlobal = 60 â†’ recommendation = A_ETUDIER")
    void lancerMatching_score60_recommendsAEtudier() throws Exception {
        // Given â€” skill at 0.60
        when(pythonClient.isAvailable()).thenReturn(true);
        when(candidateRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(makeCandidate()));

        TechnicalSkill skill = makeSkill("Docker", false);
        Job job = makeJob(List.of(skill), Collections.emptyList());
        when(jobRepository.findByIdWithSkills(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.findByIdWithPrerequisites(JOB_ID)).thenReturn(Optional.of(job));

        when(reportRepository.findByJobIdAndCandidateIdAndCvHash(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(pythonClient.extraireCv(any(), any(), any(), any(), any()))
            .thenReturn(makeExtraction(List.of(makeSkillEvalue("Docker", 0.60)), Collections.emptyList()));

        when(reportRepository.findByJobIdAndCandidateId(JOB_ID, CANDIDATE_ID))
            .thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenReturn(makeSavedReport(60.0, "A_ETUDIER"));
        when(jobMatchRepository.findByCandidateIdAndJobId(CANDIDATE_ID, JOB_ID))
            .thenReturn(Optional.empty());
        when(jobMatchRepository.save(any())).thenReturn(new JobMatch());

        // When
        var dto = svc.lancerMatching(JOB_ID, CANDIDATE_ID, CV_BYTES, CV_FILENAME);

        // Then
        assertEquals("A_ETUDIER", dto.getRecommendation());
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // TC-SC-10 â€” Recommandation REJETER (score < 50, pas de rejet forcÃ©)
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Test
    @Order(10)
    @DisplayName("TC-SC-10 : scoreGlobal < 50 â†’ recommendation = REJETER (sans rejet forcÃ©)")
    void lancerMatching_scoreBelowThreshold_recommendsRejeter() throws Exception {
        // Given â€” non-mandatory skill at 0.30 â†’ score â‰ˆ 30%
        when(pythonClient.isAvailable()).thenReturn(true);
        when(candidateRepository.findById(CANDIDATE_ID)).thenReturn(Optional.of(makeCandidate()));

        TechnicalSkill skill = makeSkill("Kubernetes", false);
        Job job = makeJob(List.of(skill), Collections.emptyList());
        when(jobRepository.findByIdWithSkills(JOB_ID)).thenReturn(Optional.of(job));
        when(jobRepository.findByIdWithPrerequisites(JOB_ID)).thenReturn(Optional.of(job));

        when(reportRepository.findByJobIdAndCandidateIdAndCvHash(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(pythonClient.extraireCv(any(), any(), any(), any(), any()))
            .thenReturn(makeExtraction(List.of(makeSkillEvalue("Kubernetes", 0.30)), Collections.emptyList()));

        when(reportRepository.findByJobIdAndCandidateId(JOB_ID, CANDIDATE_ID))
            .thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenReturn(makeSavedReport(30.0, "REJETER"));
        when(jobMatchRepository.findByCandidateIdAndJobId(CANDIDATE_ID, JOB_ID))
            .thenReturn(Optional.empty());
        when(jobMatchRepository.save(any())).thenReturn(new JobMatch());

        // When
        var dto = svc.lancerMatching(JOB_ID, CANDIDATE_ID, CV_BYTES, CV_FILENAME);

        // Then
        assertEquals("REJETER", dto.getRecommendation());
        assertFalse(dto.isForceRejet(), "This is a soft reject (low score), not a forced one");
    }
}
