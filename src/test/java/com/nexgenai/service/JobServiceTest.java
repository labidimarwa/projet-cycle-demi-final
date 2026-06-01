package com.nexgenai.service;

import com.nexgenai.dto.job.CreateJobRequest;
import com.nexgenai.dto.job.JobResponse;
import com.nexgenai.dto.job.UpdateJobRequest;
import com.nexgenai.model.Job;
import com.nexgenai.model.enums.ContractType;
import com.nexgenai.model.enums.ExperienceLevel;
import com.nexgenai.model.enums.JobStatus;
import com.nexgenai.model.enums.StageType;
import com.nexgenai.repository.ApplicationRepository;
import com.nexgenai.repository.JobRepository;
import com.nexgenai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    @Mock private JobRepository         jobRepository;
    @Mock private ApplicationRepository applicationRepository;
    @Mock private InterviewService      interviewService;
    @Mock private NotificationService   notificationService;
    @Mock private UserRepository        userRepository;
    @Mock private PythonExtractorClient pythonClient;

    @InjectMocks private JobService jobService;

    private Job sampleJob;
    private CreateJobRequest validRequest;

    // ── Helper : prérequis valide ─────────────────────────────────────────────
    private CreateJobRequest.PrerequisiteDTO prereq(String value) {
        CreateJobRequest.PrerequisiteDTO p = new CreateJobRequest.PrerequisiteDTO();
        p.setType("DEGREE");
        p.setValue(value);
        p.setObligatory(true);
        return p;
    }

    // ── Helper : skill valide ─────────────────────────────────────────────────
    private CreateJobRequest.TechnicalSkillDTO skill(String name) {
        CreateJobRequest.TechnicalSkillDTO s = new CreateJobRequest.TechnicalSkillDTO();
        s.setName(name);
        s.setObligatory(true);
        s.setSkillType("TECHNICAL");
        return s;
    }

    // ── Helper : stage avec assignee ─────────────────────────────────────────
    private CreateJobRequest.WorkflowStageDTO stage(StageType type, String assigneeId) {
        CreateJobRequest.WorkflowStageDTO w = new CreateJobRequest.WorkflowStageDTO();
        w.setStageType(type);
        w.setName(type.name());
        w.setAssigneeId(assigneeId);
        return w;
    }

    @BeforeEach
    void setUp() {
        sampleJob = new Job();
        sampleJob.setId("job-uuid-001");
        sampleJob.setTitle("Développeur Java Senior");
        sampleJob.setDepartment("IT");
        sampleJob.setLocation("Tunis");
        sampleJob.setContractType(ContractType.CONTRACT);
        sampleJob.setExperienceLevel(ExperienceLevel.SENIOR);
        sampleJob.setDescription("Poste de développeur Java Spring Boot");
        sampleJob.setStatus(JobStatus.ACTIVE);
        sampleJob.setOpenPositions(2);
        sampleJob.setIsRemote(false);

        // Requête minimale valide : poids 100 % + au moins 1 prérequis + 1 skill
        validRequest = new CreateJobRequest();
        validRequest.setTitle("Développeur Java Senior");
        validRequest.setDepartment("IT");
        validRequest.setLocation("Tunis");
        validRequest.setContractType(ContractType.CONTRACT);
        validRequest.setExperienceLevel(ExperienceLevel.SENIOR);
        validRequest.setDescription("Poste de développeur Java Spring Boot");
        validRequest.setOpenPositions(2);
        validRequest.setIsRemote(false);
        validRequest.setSkillsWeight(70);
        validRequest.setPrerequisitesWeight(30);
        validRequest.setTechnicalSkillWeight(60);
        validRequest.setSoftSkillWeight(40);
        validRequest.setPrerequisites(List.of(prereq("Bac+3")));
        validRequest.setTechnicalSkills(List.of(skill("Java")));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-21 : CRÉATION D'UNE ANNONCE
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("US-21 : Création d'une annonce")
    class CreateJobTests {

        @Test
        @DisplayName("Requête valide → retourne JobResponse avec statut ACTIVE")
        void createJob_validRequest_returnsJobResponse() {
            when(jobRepository.save(any(Job.class))).thenReturn(sampleJob);
            when(applicationRepository.countByJobId("job-uuid-001")).thenReturn(0L);
            doNothing().when(interviewService).createInterviewsForJob(any());

            JobResponse response = jobService.createJob(validRequest, null);

            assertNotNull(response);
            assertEquals("Développeur Java Senior", response.getTitle());
            verify(jobRepository, times(1)).save(any(Job.class));
            verify(interviewService, times(1)).createInterviewsForJob(any(Job.class));
        }

        @Test
        @DisplayName("Poids global ≠ 100 (80+30=110) → IllegalArgumentException")
        void createJob_globalWeightsNot100_throwsException() {
            validRequest.setSkillsWeight(80);
            validRequest.setPrerequisitesWeight(30);

            assertThrows(IllegalArgumentException.class,
                () -> jobService.createJob(validRequest, null));
            verify(jobRepository, never()).save(any());
        }

        @Test
        @DisplayName("Poids sous-section skills ≠ 100 (70+40=110) → IllegalArgumentException")
        void createJob_skillSubWeightsNot100_throwsException() {
            validRequest.setTechnicalSkillWeight(70);
            validRequest.setSoftSkillWeight(40);

            assertThrows(IllegalArgumentException.class,
                () -> jobService.createJob(validRequest, null));
            verify(jobRepository, never()).save(any());
        }

        @Test
        @DisplayName("Aucun prérequis → IllegalArgumentException")
        void createJob_noPrerequisites_throwsException() {
            validRequest.setPrerequisites(null);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> jobService.createJob(validRequest, null));
            assertTrue(ex.getMessage().contains("prérequis"));
        }

        @Test
        @DisplayName("Prérequis avec value null → IllegalArgumentException")
        void createJob_prerequisiteWithNullValue_throwsException() {
            validRequest.setPrerequisites(List.of(prereq(null)));

            assertThrows(IllegalArgumentException.class,
                () -> jobService.createJob(validRequest, null));
        }

        @Test
        @DisplayName("Aucune compétence → IllegalArgumentException")
        void createJob_noSkills_throwsException() {
            validRequest.setTechnicalSkills(null);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> jobService.createJob(validRequest, null));
            assertTrue(ex.getMessage().contains("compétence"));
        }

        @Test
        @DisplayName("Stage TECHNICAL_INTERVIEW sans assigneeId → IllegalArgumentException")
        void createJob_interviewStageWithoutAssignee_throwsException() {
            validRequest.setWorkflowStages(List.of(stage(StageType.TECHNICAL_INTERVIEW, null)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> jobService.createJob(validRequest, null));
            assertTrue(ex.getMessage().contains("assigné"));
        }

        @Test
        @DisplayName("Stage TECHNICAL_TEST sans assigneeId → IllegalArgumentException")
        void createJob_testStageWithoutAssignee_throwsException() {
            validRequest.setWorkflowStages(List.of(stage(StageType.TECHNICAL_TEST, "")));

            assertThrows(IllegalArgumentException.class,
                () -> jobService.createJob(validRequest, null));
        }

        @Test
        @DisplayName("Stage AI_SCREENING sans assigneeId → OK (automatisé)")
        void createJob_aiScreeningWithoutAssignee_succeeds() {
            validRequest.setWorkflowStages(List.of(stage(StageType.AI_SCREENING, null)));
            when(jobRepository.save(any(Job.class))).thenReturn(sampleJob);
            when(applicationRepository.countByJobId(any())).thenReturn(0L);
            doNothing().when(interviewService).createInterviewsForJob(any());

            assertDoesNotThrow(() -> jobService.createJob(validRequest, null));
        }

        @Test
        @DisplayName("Stage TECHNICAL_INTERVIEW avec assigneeId → OK")
        void createJob_interviewStageWithAssignee_succeeds() {
            validRequest.setWorkflowStages(List.of(stage(StageType.TECHNICAL_INTERVIEW, "hr-user-001")));
            when(jobRepository.save(any(Job.class))).thenReturn(sampleJob);
            when(applicationRepository.countByJobId(any())).thenReturn(0L);
            doNothing().when(interviewService).createInterviewsForJob(any());

            assertDoesNotThrow(() -> jobService.createJob(validRequest, null));
        }

        @Test
        @DisplayName("Avec prérequis et skills → prérequis et skills ajoutés au job sauvegardé")
        void createJob_withPrerequisitesAndSkills_savedCorrectly() {
            when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
                Job j = inv.getArgument(0);
                j.setId("job-uuid-002");
                return j;
            });
            when(applicationRepository.countByJobId(any())).thenReturn(0L);
            doNothing().when(interviewService).createInterviewsForJob(any());

            jobService.createJob(validRequest, null);

            verify(jobRepository).save(argThat(job ->
                job.getPrerequisites() != null && job.getPrerequisites().size() == 1
                && job.getTechnicalSkills() != null && job.getTechnicalSkills().size() == 1
            ));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-24 : MACHINE D'ÉTATS — CHANGEMENT DE STATUT
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("US-24 : Machine d'états — changement de statut")
    class StatusTransitionTests {

        private Job jobWithStatus(JobStatus status) {
            Job j = new Job();
            j.setId("job-uuid-001");
            j.setTitle("Test Job");
            j.setStatus(status);
            return j;
        }

        // ── Transitions AUTORISÉES ─────────────────────────────────────────────

        @Test
        @DisplayName("DRAFT → ACTIVE (publier) ✅")
        void transition_draftToActive_allowed() {
            when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(jobWithStatus(JobStatus.DRAFT)));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(applicationRepository.countByJobId(any())).thenReturn(0L);

            JobResponse r = jobService.changeStatus("job-uuid-001", JobStatus.ACTIVE);

            assertEquals(JobStatus.ACTIVE, r.getStatus());
        }

        @Test
        @DisplayName("ACTIVE → PAUSED (suspendre) ✅")
        void transition_activeToPaused_allowed() {
            when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(jobWithStatus(JobStatus.ACTIVE)));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(applicationRepository.countByJobId(any())).thenReturn(0L);

            JobResponse r = jobService.changeStatus("job-uuid-001", JobStatus.PAUSED);

            assertEquals(JobStatus.PAUSED, r.getStatus());
        }

        @Test
        @DisplayName("ACTIVE → CLOSED (clôturer) ✅")
        void transition_activeToClosed_allowed() {
            when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(jobWithStatus(JobStatus.ACTIVE)));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(applicationRepository.findByJobId(any())).thenReturn(List.of());
            when(applicationRepository.countByJobId(any())).thenReturn(0L);

            JobResponse r = jobService.changeStatus("job-uuid-001", JobStatus.CLOSED);

            assertEquals(JobStatus.CLOSED, r.getStatus());
            verify(jobRepository).save(argThat(j -> j.getStatus() == JobStatus.CLOSED));
        }

        @Test
        @DisplayName("PAUSED → ACTIVE (réactiver) ✅")
        void transition_pausedToActive_allowed() {
            when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(jobWithStatus(JobStatus.PAUSED)));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(applicationRepository.countByJobId(any())).thenReturn(0L);

            JobResponse r = jobService.changeStatus("job-uuid-001", JobStatus.ACTIVE);

            assertEquals(JobStatus.ACTIVE, r.getStatus());
        }

        @Test
        @DisplayName("PAUSED → CLOSED (clôturer) ✅")
        void transition_pausedToClosed_allowed() {
            when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(jobWithStatus(JobStatus.PAUSED)));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(applicationRepository.findByJobId(any())).thenReturn(List.of());
            when(applicationRepository.countByJobId(any())).thenReturn(0L);

            JobResponse r = jobService.changeStatus("job-uuid-001", JobStatus.CLOSED);

            assertEquals(JobStatus.CLOSED, r.getStatus());
        }

        // ── Transitions INTERDITES ─────────────────────────────────────────────

        @Test
        @DisplayName("DRAFT → PAUSED ❌ interdit → IllegalStateException")
        void transition_draftToPaused_forbidden() {
            when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(jobWithStatus(JobStatus.DRAFT)));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> jobService.changeStatus("job-uuid-001", JobStatus.PAUSED));
            assertTrue(ex.getMessage().contains("DRAFT"));
            verify(jobRepository, never()).save(any());
        }

        @Test
        @DisplayName("CLOSED → ACTIVE ❌ interdit → IllegalStateException")
        void transition_closedToActive_forbidden() {
            when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(jobWithStatus(JobStatus.CLOSED)));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> jobService.changeStatus("job-uuid-001", JobStatus.ACTIVE));
            assertTrue(ex.getMessage().contains("CLOSED"));
            verify(jobRepository, never()).save(any());
        }

        @Test
        @DisplayName("CLOSED → PAUSED ❌ interdit → IllegalStateException")
        void transition_closedToPaused_forbidden() {
            when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(jobWithStatus(JobStatus.CLOSED)));

            assertThrows(IllegalStateException.class,
                () -> jobService.changeStatus("job-uuid-001", JobStatus.PAUSED));
            verify(jobRepository, never()).save(any());
        }

        @Test
        @DisplayName("ID inexistant → RuntimeException")
        void changeStatus_nonExistingId_throwsRuntimeException() {
            when(jobRepository.findById("bad-id")).thenReturn(Optional.empty());

            assertThrows(RuntimeException.class,
                () -> jobService.changeStatus("bad-id", JobStatus.ACTIVE));
        }

        @Test
        @DisplayName("ACTIVE → CLOSED déclenche notification des candidats")
        void transition_activeToClosed_notifiesApplicants() {
            Job job = jobWithStatus(JobStatus.ACTIVE);
            com.nexgenai.model.Application app = new com.nexgenai.model.Application();
            app.setCandidateId("cand-001");

            when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(job));
            when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(applicationRepository.findByJobId("job-uuid-001")).thenReturn(List.of(app));
            when(applicationRepository.countByJobId(any())).thenReturn(1L);

            jobService.changeStatus("job-uuid-001", JobStatus.CLOSED);

            verify(notificationService, times(1)).send(
                eq("cand-001"), any(), any(), any(), any(), any(), any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-25 : LECTURE
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("US-25 : getAllJobs - retourne liste de JobResponse")
    void getAllJobs_returnsListOfJobResponses() {
        Job job2 = new Job();
        job2.setId("job-uuid-002");
        job2.setTitle("Data Scientist");
        job2.setStatus(JobStatus.ACTIVE);

        when(jobRepository.findAll()).thenReturn(List.of(sampleJob, job2));
        when(applicationRepository.countByJobId("job-uuid-001")).thenReturn(3L);
        when(applicationRepository.countByJobId("job-uuid-002")).thenReturn(1L);

        List<JobResponse> result = jobService.getAllJobs();

        assertEquals(2, result.size());
        assertEquals(3, result.get(0).getApplicantsCount());
    }

    @Test
    @DisplayName("US-13 : getActiveJobs - retourne uniquement les jobs ACTIVE")
    void getActiveJobs_returnsOnlyActiveJobs() {
        when(jobRepository.findByStatus(JobStatus.ACTIVE)).thenReturn(List.of(sampleJob));
        when(applicationRepository.countByJobId("job-uuid-001")).thenReturn(5L);

        List<JobResponse> result = jobService.getActiveJobs();

        assertEquals(1, result.size());
        assertEquals(JobStatus.ACTIVE, result.get(0).getStatus());
    }

    @Test
    @DisplayName("US-22 : getJobById - ID existant → retourne le job")
    void getJobById_existingId_returnsJobResponse() {
        when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(sampleJob));
        when(applicationRepository.countByJobId("job-uuid-001")).thenReturn(2L);

        JobResponse response = jobService.getJobById("job-uuid-001");

        assertNotNull(response);
        assertEquals("job-uuid-001", response.getId());
        assertEquals(2, response.getApplicantsCount());
    }

    @Test
    @DisplayName("US-22 : getJobById - ID inexistant → RuntimeException")
    void getJobById_nonExistingId_throwsRuntimeException() {
        when(jobRepository.findById("bad-id")).thenReturn(Optional.empty());

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> jobService.getJobById("bad-id"));
        assertTrue(ex.getMessage().contains("bad-id"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-23 : SUPPRESSION
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("US-23 : deleteJob - ID existant → suppression réussie")
    void deleteJob_existingId_deletesSuccessfully() {
        when(jobRepository.existsById("job-uuid-001")).thenReturn(true);
        doNothing().when(jobRepository).deleteById("job-uuid-001");

        assertDoesNotThrow(() -> jobService.deleteJob("job-uuid-001"));
        verify(jobRepository, times(1)).deleteById("job-uuid-001");
    }

    @Test
    @DisplayName("US-23 : deleteJob - ID inexistant → RuntimeException, deleteById jamais appelé")
    void deleteJob_nonExistingId_throwsAndNeverCallsDelete() {
        when(jobRepository.existsById("bad-id")).thenReturn(false);

        assertThrows(RuntimeException.class, () -> jobService.deleteJob("bad-id"));
        verify(jobRepository, never()).deleteById(any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-22 : MISE À JOUR
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("US-22 : updateJob - champs mis à jour")
    void updateJob_existingId_updatesFields() {
        when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(sampleJob));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(applicationRepository.countByJobId(any())).thenReturn(0L);

        CreateJobRequest req = new CreateJobRequest();
        req.setTitle("Lead Developer");
        req.setDepartment("R&D");
        req.setLocation("Sfax");
        req.setContractType(ContractType.CONTRACT);
        req.setExperienceLevel(ExperienceLevel.MID_LEVEL);

        JobResponse response = jobService.updateJob("job-uuid-001", req);

        assertEquals("Lead Developer", response.getTitle());
        assertEquals("R&D", response.getDepartment());
    }

    @Test
    @DisplayName("US-22 : patchJob - titre seul → seul le titre est modifié")
    void patchJob_titleOnly_onlyTitleUpdated() {
        when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(sampleJob));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(applicationRepository.countByJobId(any())).thenReturn(0L);

        UpdateJobRequest req = new UpdateJobRequest();
        req.setTitle("Senior Java Engineer");

        JobResponse response = jobService.patchJob("job-uuid-001", req);

        assertEquals("Senior Java Engineer", response.getTitle());
        assertEquals("IT", response.getDepartment());
    }

    @Test
    @DisplayName("mapToResponse - applicantsCount reflète le nombre réel")
    void mapToResponse_applicantsCount_reflectsRealCount() {
        when(jobRepository.findById("job-uuid-001")).thenReturn(Optional.of(sampleJob));
        when(applicationRepository.countByJobId("job-uuid-001")).thenReturn(42L);

        assertEquals(42, jobService.getJobById("job-uuid-001").getApplicantsCount());
    }
}
