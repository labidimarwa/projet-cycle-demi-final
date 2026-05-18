package com.nexgenai.service;

import com.nexgenai.dto.job.CreateJobRequest;
import com.nexgenai.dto.job.JobResponse;
import com.nexgenai.dto.job.UpdateJobRequest;
import com.nexgenai.model.Job;
import com.nexgenai.model.enums.ContractType;
import com.nexgenai.model.enums.ExperienceLevel;
import com.nexgenai.model.enums.JobStatus;
import com.nexgenai.repository.ApplicationRepository;
import com.nexgenai.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JobService - Tests Unitaires")
class JobServiceTest {

    // ── Mocks des dépendances ──────────────────────────────────────────────────
    @Mock private JobRepository jobRepository;
    @Mock private ApplicationRepository applicationRepository;
    @Mock private InterviewService interviewService;

    // ── Classe testée ─────────────────────────────────────────────────────────
    @InjectMocks private JobService jobService;

    // ── Données de test réutilisables ─────────────────────────────────────────
    private Job sampleJob;
    private CreateJobRequest validRequest;

    @BeforeEach
    void setUp() {
        // Job de référence (simule ce que retourne la BDD)
        sampleJob = new Job();
        sampleJob.setId("job-uuid-001");
        sampleJob.setTitle("Développeur Java Senior");
        sampleJob.setDepartment("IT");
        sampleJob.setLocation("Tunis");
        sampleJob.setContractType(ContractType.CDI);
        sampleJob.setExperienceLevel(ExperienceLevel.SENIOR);
        sampleJob.setDescription("Poste de développeur Java Spring Boot");
        sampleJob.setStatus(JobStatus.ACTIVE);
        sampleJob.setOpenPositions(2);
        sampleJob.setIsRemote(false);

        // Requête de création valide
        validRequest = new CreateJobRequest();
        validRequest.setTitle("Développeur Java Senior");
        validRequest.setDepartment("IT");
        validRequest.setLocation("Tunis");
        validRequest.setContractType(ContractType.CDI);
        validRequest.setExperienceLevel(ExperienceLevel.SENIOR);
        validRequest.setDescription("Poste de développeur Java Spring Boot");
        validRequest.setOpenPositions(2);
        validRequest.setIsRemote(false);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-21 : CRÉATION D'UNE ANNONCE
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("US-21 : createJob - requête valide → retourne JobResponse avec statut ACTIVE")
    void createJob_validRequest_returnsJobResponseWithActiveStatus() {
        // GIVEN
        when(jobRepository.save(any(Job.class))).thenReturn(sampleJob);
        when(applicationRepository.countByJobId("job-uuid-001")).thenReturn(0L);
        doNothing().when(interviewService).createInterviewsForJob(any(Job.class));

        // WHEN
        JobResponse response = jobService.createJob(validRequest);

        // THEN
        assertNotNull(response, "La réponse ne doit pas être null");
        assertEquals("Développeur Java Senior", response.getTitle());
        assertEquals("IT", response.getDepartment());
        assertEquals(JobStatus.ACTIVE, response.getStatus());
        assertEquals(0, response.getApplicantsCount());

        // Vérifier que save() et createInterviewsForJob() ont été appelés
        verify(jobRepository, times(1)).save(any(Job.class));
        verify(interviewService, times(1)).createInterviewsForJob(any(Job.class));
    }

    @Test
    @DisplayName("US-21 : createJob - sans prérequis ni skills → sauvegarde quand même")
    void createJob_noPrerequisitesNoSkills_savesCalled() {
        // GIVEN : requête minimale (sans listes)
        validRequest.setPrerequisites(null);
        validRequest.setTechnicalSkills(null);
        validRequest.setAssessments(null);
        validRequest.setWorkflowStages(null);

        when(jobRepository.save(any(Job.class))).thenReturn(sampleJob);
        when(applicationRepository.countByJobId(any())).thenReturn(0L);
        doNothing().when(interviewService).createInterviewsForJob(any());

        // WHEN
        JobResponse response = jobService.createJob(validRequest);

        // THEN
        assertNotNull(response);
        verify(jobRepository, times(1)).save(any(Job.class));
    }

    @Test
    @DisplayName("US-21 : createJob - avec prérequis → prérequis ajoutés au job")
    void createJob_withPrerequisites_prerequisitesAddedToJob() {
        // GIVEN
        CreateJobRequest.PrerequisiteDTO prereq = new CreateJobRequest.PrerequisiteDTO();
        prereq.setType("EDUCATION");
        prereq.setValue("Bac+5");
        prereq.setObligatory(true);
        validRequest.setPrerequisites(List.of(prereq));

        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> {
            Job savedJob = invocation.getArgument(0);
            savedJob.setId("job-uuid-002");
            return savedJob;
        });
        when(applicationRepository.countByJobId(any())).thenReturn(0L);
        doNothing().when(interviewService).createInterviewsForJob(any());

        // WHEN
        JobResponse response = jobService.createJob(validRequest);

        // THEN
        assertNotNull(response);
        verify(jobRepository).save(argThat(job ->
            job.getPrerequisites() != null && job.getPrerequisites().size() == 1
        ));
    }

    @Test
    @DisplayName("US-21 : createJob - avec technicalSkills → skills ajoutés au job")
    void createJob_withTechnicalSkills_skillsAddedToJob() {
        // GIVEN
        CreateJobRequest.TechnicalSkillDTO skill = new CreateJobRequest.TechnicalSkillDTO();
        skill.setName("Spring Boot");
        skill.setObligatory(true);
        skill.setWeight(80);
        validRequest.setTechnicalSkills(List.of(skill));

        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
            Job j = inv.getArgument(0);
            j.setId("job-uuid-003");
            return j;
        });
        when(applicationRepository.countByJobId(any())).thenReturn(0L);
        doNothing().when(interviewService).createInterviewsForJob(any());

        // WHEN
        jobService.createJob(validRequest);

        // THEN
        verify(jobRepository).save(argThat(job ->
            job.getTechnicalSkills() != null && job.getTechnicalSkills().size() == 1
        ));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-25 : LECTURE DE TOUTES LES ANNONCES
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("US-25 : getAllJobs - retourne liste de JobResponse")
    void getAllJobs_returnsListOfJobResponses() {
        // GIVEN
        Job job2 = new Job();
        job2.setId("job-uuid-002");
        job2.setTitle("Data Scientist");
        job2.setStatus(JobStatus.ACTIVE);

        when(jobRepository.findAll()).thenReturn(List.of(sampleJob, job2));
        when(applicationRepository.countByJobId("job-uuid-001")).thenReturn(3L);
        when(applicationRepository.countByJobId("job-uuid-002")).thenReturn(1L);

        // WHEN
        List<JobResponse> result = jobService.getAllJobs();

        // THEN
        assertEquals(2, result.size());
        assertEquals("Développeur Java Senior", result.get(0).getTitle());
        assertEquals(3, result.get(0).getApplicantsCount());
        assertEquals("Data Scientist", result.get(1).getTitle());
        assertEquals(1, result.get(1).getApplicantsCount());
    }

    @Test
    @DisplayName("US-25 : getAllJobs - liste vide → retourne liste vide")
    void getAllJobs_emptyRepository_returnsEmptyList() {
        // GIVEN
        when(jobRepository.findAll()).thenReturn(List.of());

        // WHEN
        List<JobResponse> result = jobService.getAllJobs();

        // THEN
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-13 : ANNONCES ACTIVES (pour les candidats)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("US-13 : getActiveJobs - retourne uniquement les jobs ACTIVE")
    void getActiveJobs_returnsOnlyActiveJobs() {
        // GIVEN
        when(jobRepository.findByStatus(JobStatus.ACTIVE)).thenReturn(List.of(sampleJob));
        when(applicationRepository.countByJobId("job-uuid-001")).thenReturn(5L);

        // WHEN
        List<JobResponse> result = jobService.getActiveJobs();

        // THEN
        assertEquals(1, result.size());
        assertEquals(JobStatus.ACTIVE, result.get(0).getStatus());
        verify(jobRepository).findByStatus(JobStatus.ACTIVE);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-22 : LECTURE PAR ID
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("US-22 : getJobById - ID existant → retourne le job")
    void getJobById_existingId_returnsJobResponse() {
        // GIVEN
        when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(sampleJob));
        when(applicationRepository.countByJobId("job-uuid-001")).thenReturn(2L);

        // WHEN
        JobResponse response = jobService.getJobById("job-uuid-001");

        // THEN
        assertNotNull(response);
        assertEquals("job-uuid-001", response.getId());
        assertEquals("Développeur Java Senior", response.getTitle());
        assertEquals(2, response.getApplicantsCount());
    }

    @Test
    @DisplayName("US-22 : getJobById - ID inexistant → RuntimeException avec message")
    void getJobById_nonExistingId_throwsRuntimeException() {
        // GIVEN
        when(jobRepository.findById("bad-id")).thenReturn(Optional.empty());

        // WHEN + THEN
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> jobService.getJobById("bad-id"));
        assertTrue(ex.getMessage().contains("bad-id"),
            "Le message d'erreur doit contenir l'ID manquant");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-14 : FILTRAGE PAR DÉPARTEMENT
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("US-14 : getJobsByDepartment - département existant → retourne les jobs")
    void getJobsByDepartment_existingDepartment_returnsJobs() {
        // GIVEN
        when(jobRepository.findByDepartment("IT")).thenReturn(List.of(sampleJob));
        when(applicationRepository.countByJobId("job-uuid-001")).thenReturn(0L);

        // WHEN
        List<JobResponse> result = jobService.getJobsByDepartment("IT");

        // THEN
        assertEquals(1, result.size());
        assertEquals("IT", result.get(0).getDepartment());
    }

    @Test
    @DisplayName("US-14 : getJobsByDepartment - département inexistant → liste vide")
    void getJobsByDepartment_nonExistingDepartment_returnsEmptyList() {
        // GIVEN
        when(jobRepository.findByDepartment("UNKNOWN")).thenReturn(List.of());

        // WHEN
        List<JobResponse> result = jobService.getJobsByDepartment("UNKNOWN");

        // THEN
        assertTrue(result.isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-24 : CHANGEMENT DE STATUT
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("US-24 : changeStatus - ACTIVE → CLOSED → statut mis à jour")
    void changeStatus_activeToClose_updatesStatus() {
        // GIVEN
        when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(sampleJob));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
        when(applicationRepository.countByJobId(any())).thenReturn(0L);

        // WHEN
        JobResponse response = jobService.changeStatus("job-uuid-001", JobStatus.CLOSED);

        // THEN
        assertEquals(JobStatus.CLOSED, response.getStatus());
        verify(jobRepository).save(argThat(job -> job.getStatus() == JobStatus.CLOSED));
    }

    @Test
    @DisplayName("US-24 : changeStatus - ACTIVE → HIDDEN → statut mis à jour")
    void changeStatus_activeToHidden_updatesStatus() {
        // GIVEN
        when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(sampleJob));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
        when(applicationRepository.countByJobId(any())).thenReturn(0L);

        // WHEN
        JobResponse response = jobService.changeStatus("job-uuid-001", JobStatus.HIDDEN);

        // THEN
        assertEquals(JobStatus.HIDDEN, response.getStatus());
    }

    @Test
    @DisplayName("US-24 : changeStatus - ID inexistant → RuntimeException")
    void changeStatus_nonExistingId_throwsRuntimeException() {
        // GIVEN
        when(jobRepository.findById("bad-id")).thenReturn(Optional.empty());

        // WHEN + THEN
        assertThrows(RuntimeException.class,
            () -> jobService.changeStatus("bad-id", JobStatus.CLOSED));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-23 : SUPPRESSION D'UNE ANNONCE
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("US-23 : deleteJob - ID existant → suppression réussie")
    void deleteJob_existingId_deletesSuccessfully() {
        // GIVEN
        when(jobRepository.existsById("job-uuid-001")).thenReturn(true);
        doNothing().when(jobRepository).deleteById("job-uuid-001");

        // WHEN + THEN
        assertDoesNotThrow(() -> jobService.deleteJob("job-uuid-001"));
        verify(jobRepository, times(1)).deleteById("job-uuid-001");
    }

    @Test
    @DisplayName("US-23 : deleteJob - ID inexistant → RuntimeException, deleteById jamais appelé")
    void deleteJob_nonExistingId_throwsRuntimeExceptionAndNeverCallsDelete() {
        // GIVEN
        when(jobRepository.existsById("bad-id")).thenReturn(false);

        // WHEN + THEN
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> jobService.deleteJob("bad-id"));
        assertTrue(ex.getMessage().contains("bad-id"));
        verify(jobRepository, never()).deleteById(any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-22 : MISE À JOUR COMPLÈTE (PUT)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("US-22 : updateJob - ID existant → champs mis à jour")
    void updateJob_existingId_updatesFields() {
        // GIVEN
        when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(sampleJob));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
        when(applicationRepository.countByJobId(any())).thenReturn(0L);

        CreateJobRequest updateReq = new CreateJobRequest();
        updateReq.setTitle("Lead Developer");
        updateReq.setDepartment("R&D");
        updateReq.setLocation("Sfax");
        updateReq.setContractType(ContractType.CDD);
        updateReq.setExperienceLevel(ExperienceLevel.MID);

        // WHEN
        JobResponse response = jobService.updateJob("job-uuid-001", updateReq);

        // THEN
        assertEquals("Lead Developer", response.getTitle());
        assertEquals("R&D", response.getDepartment());
        assertEquals("Sfax", response.getLocation());
    }

    @Test
    @DisplayName("US-22 : updateJob - ID inexistant → RuntimeException")
    void updateJob_nonExistingId_throwsRuntimeException() {
        // GIVEN
        when(jobRepository.findById("bad-id")).thenReturn(Optional.empty());

        // WHEN + THEN
        assertThrows(RuntimeException.class,
            () -> jobService.updateJob("bad-id", validRequest));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-22 : MISE À JOUR PARTIELLE (PATCH)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("US-22 : patchJob - titre seul → seul le titre est modifié")
    void patchJob_titleOnly_onlyTitleUpdated() {
        // GIVEN
        when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(sampleJob));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
        when(applicationRepository.countByJobId(any())).thenReturn(0L);

        UpdateJobRequest patchReq = new UpdateJobRequest();
        patchReq.setTitle("Senior Java Engineer");
        // Tous les autres champs sont null → ne doivent pas être modifiés

        // WHEN
        JobResponse response = jobService.patchJob("job-uuid-001", patchReq);

        // THEN
        assertEquals("Senior Java Engineer", response.getTitle());
        // Le département original doit être conservé
        assertEquals("IT", response.getDepartment());
    }

    @Test
    @DisplayName("US-22 : patchJob - statut seul → seul le statut est modifié")
    void patchJob_statusOnly_onlyStatusUpdated() {
        // GIVEN
        when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(sampleJob));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
        when(applicationRepository.countByJobId(any())).thenReturn(0L);

        UpdateJobRequest patchReq = new UpdateJobRequest();
        patchReq.setStatus(JobStatus.DRAFT);

        // WHEN
        JobResponse response = jobService.patchJob("job-uuid-001", patchReq);

        // THEN
        assertEquals(JobStatus.DRAFT, response.getStatus());
        assertEquals("Développeur Java Senior", response.getTitle()); // inchangé
    }

    @Test
    @DisplayName("US-22 : patchJob - ID inexistant → RuntimeException")
    void patchJob_nonExistingId_throwsRuntimeException() {
        // GIVEN
        when(jobRepository.findById("bad-id")).thenReturn(Optional.empty());

        // WHEN + THEN
        assertThrows(RuntimeException.class,
            () -> jobService.patchJob("bad-id", new UpdateJobRequest()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TESTS DE MAPPING (mapToResponse)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("mapToResponse - job avec isRemote=true → isRemote=true dans la réponse")
    void mapToResponse_remoteJob_isRemoteTrue() {
        // GIVEN
        sampleJob.setIsRemote(true);
        when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(sampleJob));
        when(applicationRepository.countByJobId(any())).thenReturn(0L);

        // WHEN
        JobResponse response = jobService.getJobById("job-uuid-001");

        // THEN
        assertTrue(response.getIsRemote());
    }

    @Test
    @DisplayName("mapToResponse - applicantsCount reflète le nombre réel de candidatures")
    void mapToResponse_applicantsCount_reflectsRealCount() {
        // GIVEN
        when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(sampleJob));
        when(applicationRepository.countByJobId("job-uuid-001")).thenReturn(42L);

        // WHEN
        JobResponse response = jobService.getJobById("job-uuid-001");

        // THEN
        assertEquals(42, response.getApplicantsCount());
    }
}