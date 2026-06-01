package com.nexgenai.service;

import com.nexgenai.dto.job.CreateJobRequest;
import com.nexgenai.dto.job.JobResponse;
import com.nexgenai.dto.job.UpdateJobRequest;
import com.nexgenai.model.*;
import com.nexgenai.model.enums.ContractType;
import com.nexgenai.model.enums.ExperienceLevel;
import com.nexgenai.model.enums.JobStatus;
import com.nexgenai.model.enums.NotificationType;
import com.nexgenai.model.enums.StageType;
import com.nexgenai.repository.ApplicationRepository;
import com.nexgenai.repository.JobRepository;
import com.nexgenai.repository.UserRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class JobService {

    // ── Machine d'états ───────────────────────────────────────────────────────
    private static final Map<JobStatus, Set<JobStatus>> ALLOWED_TRANSITIONS = Map.of(
        JobStatus.DRAFT,  EnumSet.of(JobStatus.ACTIVE),
        JobStatus.ACTIVE, EnumSet.of(JobStatus.PAUSED, JobStatus.CLOSED),
        JobStatus.PAUSED, EnumSet.of(JobStatus.ACTIVE, JobStatus.CLOSED),
        JobStatus.CLOSED, EnumSet.noneOf(JobStatus.class)
    );

    // Étapes nécessitant un responsable humain assigné
    private static final Set<StageType> HUMAN_STAGE_TYPES = EnumSet.of(
        StageType.TECHNICAL_TEST,
        StageType.RH_TEST,
        StageType.RH_INTERVIEW,
        StageType.TECHNICAL_INTERVIEW,
        StageType.ADMIN_INTERVIEW
    );

    private final JobRepository         jobRepository;
    private final ApplicationRepository applicationRepository;
    private final InterviewService      interviewService;
    private final NotificationService   notificationService;
    private final UserRepository        userRepository;
    private final PythonExtractorClient pythonClient;

    @Value("${app.frontend-url:http://localhost:4200}")
    private String frontendBaseUrl;

    public JobService(JobRepository jobRepository, ApplicationRepository applicationRepository,
                      InterviewService interviewService, NotificationService notificationService,
                      UserRepository userRepository, PythonExtractorClient pythonClient) {
        this.jobRepository         = jobRepository;
        this.applicationRepository = applicationRepository;
        this.interviewService      = interviewService;
        this.notificationService   = notificationService;
        this.userRepository        = userRepository;
        this.pythonClient          = pythonClient;
    }

    // ── CREATE ────────────────────────────────────────────────────────────────
    @Transactional
    public JobResponse createJob(CreateJobRequest req, String createdByHrId) {
        validateJobRequest(req);
        Job job = new Job();
        applyRequestToJob(job, req);
        job.setStatus(JobStatus.DRAFT);
        job.setCreatedByHrId(createdByHrId);
        if (req.getPrerequisites() != null) {
            req.getPrerequisites().forEach(p -> {
                Prerequisite prereq = new Prerequisite();
                prereq.setType(p.getType());
                prereq.setValue(p.getValue());
                prereq.setObligatory(p.getObligatory());
                prereq.setWeight(p.getWeight() != null && p.getWeight() > 0 ? p.getWeight() : 100);
                prereq.setIcon(p.getIcon());
                prereq.setCustomType(p.getCustomType());
                if (p.getOptions() != null)
                    prereq.setOptions(String.join(",", p.getOptions()));
                prereq.setInstruction(p.getInstruction());
                prereq.setJsonSchema(p.getJsonSchema());
                job.addPrerequisite(prereq);
            });
        }

        if (req.getTechnicalSkills() != null) {
            req.getTechnicalSkills().forEach(s -> {
                TechnicalSkill skill = new TechnicalSkill();
                skill.setName(s.getName());
                skill.setObligatory(s.getObligatory());
                skill.setWeight(s.getWeight());
                skill.setSkillType(s.getSkillType() != null ? s.getSkillType() : "TECHNICAL");
                job.addTechnicalSkill(skill);
            });
        }

        if (req.getAssessments() != null) {
            req.getAssessments().forEach(a -> {
                Assessment assessment = new Assessment();
                assessment.setName(a.getName());
                assessment.setType(a.getType());
                assessment.setDuration(a.getDuration());
                assessment.setPassingScore(a.getPassingScore());
                assessment.setAssigneeId(a.getAssigneeId());
                assessment.setAssigneeName(a.getAssigneeName());
                assessment.setSubmissionDeadline(a.getSubmissionDeadline());

                // Store linkId so we can match it against workflow stages
                assessment.setLinkId(a.getLinkId());
                job.addAssessment(assessment);
            });
        }

        if (req.getWorkflowStages() != null) {
            req.getWorkflowStages().forEach(w -> {
                WorkflowStage stage = new WorkflowStage();
                stage.setStageType(w.getStageType());
                stage.setName(w.getName());
                stage.setDescription(w.getDescription());
                stage.setAssignedTo(w.getAssignedTo());
                stage.setAssigneeId(w.getAssigneeId());
                stage.setStageOrder(w.getOrder());
                // Persist the assessmentId link for stages generated from an assessment
                stage.setAssessmentId(w.getAssessmentId());
                job.addWorkflowStage(stage);
            });
        }

        Job saved = jobRepository.save(job);
        interviewService.createInterviewsForJob(saved);

        // Pre-compute job embeddings in Python asynchronously (once, reused per candidate)
        final String savedId = saved.getId();
        final List<TechnicalSkill> savedSkills = saved.getTechnicalSkills() != null
            ? List.copyOf(saved.getTechnicalSkills()) : List.of();
        final List<Prerequisite> savedPrereqs = saved.getPrerequisites() != null
            ? List.copyOf(saved.getPrerequisites()) : List.of();
        CompletableFuture.runAsync(() ->
            pythonClient.indexJob(savedId, savedSkills, savedPrereqs)
        );

        // Notify admins that a new job was created
        if (createdByHrId != null) {
            userRepository.findAll().stream()
                .filter(u -> u instanceof Admin)
                .forEach(admin -> notificationService.send(
                    admin.getId(), NotificationType.JOB_CREATED,
                    "New Job Published",
                    "A new job \"" + saved.getTitle() + "\" has been created.",
                    saved.getId(), "JOB", "/hr/jobs"
                ));
        }

        return mapToResponse(saved);
    }

    // ── READ ──────────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public JobResponse getJobById(String id) {
        return mapToResponse(jobRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Job not found: " + id)));
    }

    @Transactional(readOnly = true)
    public List<JobResponse> getAllJobs() {
        return jobRepository.findAll().stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<JobResponse> getActiveJobs() {
        return jobRepository.findByStatus(JobStatus.ACTIVE).stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<JobResponse> getJobsByDepartment(String department) {
        return jobRepository.findByDepartment(department).stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }

    // ── PATCH UPDATE (partial) ────────────────────────────────────────────────
    @Transactional
    public JobResponse patchJob(String id, UpdateJobRequest req) {
        Job job = jobRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Job not found: " + id));

        // ── Scalar fields ─────────────────────────────────────────────────────
        if (req.getTitle()           != null) job.setTitle(req.getTitle());
        if (req.getDepartment()      != null) job.setDepartment(req.getDepartment());
        if (req.getLocation()        != null) job.setLocation(req.getLocation());
        if (req.getContractType()    != null) job.setContractType(ContractType.valueOf(req.getContractType()));
        if (req.getExperienceLevel() != null) job.setExperienceLevel(ExperienceLevel.valueOf(req.getExperienceLevel()));
        if (req.getDescription()     != null) job.setDescription(req.getDescription());
        if (req.getOpenPositions()   != null) job.setOpenPositions(req.getOpenPositions());
        if (req.getClosingDate()     != null) job.setClosingDate(req.getClosingDate());
        if (req.getIsRemote()        != null) job.setIsRemote(req.getIsRemote());
        if (req.getStatus()          != null) job.setStatus(req.getStatus());
        if (req.getSkillsWeight()          != null) job.setSkillsWeight(req.getSkillsWeight());
        if (req.getPrerequisitesWeight()   != null) job.setPrerequisitesWeight(req.getPrerequisitesWeight());
        if (req.getTechnicalSkillWeight()  != null) job.setTechnicalSkillWeight(req.getTechnicalSkillWeight());
        if (req.getSoftSkillWeight()       != null) job.setSoftSkillWeight(req.getSoftSkillWeight());

        // ── Prerequisites ─────────────────────────────────────────────────────
        if (req.getPrerequisites() != null) {
            job.getPrerequisites().clear();
            req.getPrerequisites().forEach(p -> {
                Prerequisite prereq = new Prerequisite();
                prereq.setType(p.getType());
                prereq.setValue(p.getValue());
                prereq.setObligatory(p.getObligatory());
                prereq.setWeight(p.getWeight() != null && p.getWeight() > 0 ? p.getWeight() : 100);
                prereq.setIcon(p.getIcon());
                prereq.setCustomType(p.getCustomType());
                if (p.getOptions() != null)
                    prereq.setOptions(String.join(",", p.getOptions()));
                prereq.setInstruction(p.getInstruction());
                prereq.setJsonSchema(p.getJsonSchema());
                job.addPrerequisite(prereq);
            });
        }

        // ── Technical Skills ──────────────────────────────────────────────────
        if (req.getTechnicalSkills() != null) {
            job.getTechnicalSkills().clear();
            req.getTechnicalSkills().forEach(s -> {
                TechnicalSkill skill = new TechnicalSkill();
                skill.setName(s.getName());
                skill.setObligatory(s.getObligatory());
                skill.setWeight(s.getWeight());
                skill.setSkillType(s.getSkillType() != null ? s.getSkillType() : "TECHNICAL");
                job.addTechnicalSkill(skill);
            });
        }

        // ── Assessments + Workflow Stages → recréer les interviews ────────────
        boolean interviewsNeedRebuild = req.getAssessments() != null || req.getWorkflowStages() != null;

        if (req.getAssessments() != null) {
            job.getAssessments().clear();
            req.getAssessments().forEach(a -> {
                Assessment assessment = new Assessment();
                assessment.setName(a.getName());
                assessment.setType(a.getType());          // AssessmentType enum direct
                assessment.setDuration(a.getDuration());
                assessment.setPassingScore(a.getPassingScore());
                assessment.setAssigneeId(a.getAssigneeId());
                assessment.setAssigneeName(a.getAssigneeName());
                assessment.setSubmissionDeadline(a.getSubmissionDeadline());
                assessment.setLinkId(a.getLinkId());
                job.addAssessment(assessment);
            });
        }

        if (req.getWorkflowStages() != null) {
            job.getWorkflowStages().clear();
            req.getWorkflowStages().forEach(w -> {
                WorkflowStage stage = new WorkflowStage();
                stage.setStageType(w.getStageType());     // StageType enum direct
                stage.setName(w.getName());
                stage.setDescription(w.getDescription());
                stage.setAssignedTo(w.getAssignedTo());
                stage.setAssigneeId(w.getAssigneeId());
                stage.setStageOrder(w.getOrder());
                stage.setAssessmentId(w.getAssessmentId());
                job.addWorkflowStage(stage);
            });
        }

        Job saved = jobRepository.save(job);

        // Recréer les interviews si le workflow ou les assessments ont changé
        if (interviewsNeedRebuild) {
            interviewService.deleteInterviewsForJob(saved);
            interviewService.createInterviewsForJob(saved);
        }

        // Re-index job embeddings in Python if skills or prerequisites changed
        if (req.getTechnicalSkills() != null || req.getPrerequisites() != null) {
            final String savedId = saved.getId();
            final List<TechnicalSkill> savedSkills = saved.getTechnicalSkills() != null
                ? List.copyOf(saved.getTechnicalSkills()) : List.of();
            final List<Prerequisite> savedPrereqs = saved.getPrerequisites() != null
                ? List.copyOf(saved.getPrerequisites()) : List.of();
            CompletableFuture.runAsync(() ->
                pythonClient.indexJob(savedId, savedSkills, savedPrereqs)
            );
        }

        return mapToResponse(saved);
    }
    
    // ── STATUS CHANGE ─────────────────────────────────────────────────────────
    @Transactional
    public JobResponse changeStatus(String id, JobStatus newStatus) {
        Job job = jobRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Job not found: " + id));
        validateTransition(job.getStatus(), newStatus);
        job.setStatus(newStatus);
        Job saved = jobRepository.save(job);

        // Notify all applicants when the job is closed
        if (newStatus == JobStatus.CLOSED) {
            applicationRepository.findByJobId(id).forEach(app ->
                notificationService.send(
                    app.getCandidateId(), NotificationType.JOB_CLOSED,
                    "Job Closed",
                    "The position \"" + saved.getTitle() + "\" has been closed.",
                    id, "JOB", "/candidate/applications"
                )
            );
        }
        return mapToResponse(saved);
    }

    // ── DELETE ────────────────────────────────────────────────────────────────
    @Transactional
    public void deleteJob(String id) {
        if (!jobRepository.existsById(id))
            throw new RuntimeException("Job not found: " + id);
        jobRepository.deleteById(id);
        CompletableFuture.runAsync(() -> pythonClient.deleteJobIndex(id));
    }

    // ── FULL UPDATE (kept for backward compat) ────────────────────────────────
    @Transactional
    public JobResponse updateJob(String id, CreateJobRequest req) {
        Job job = jobRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Job not found: " + id));
        applyRequestToJob(job, req);
        return mapToResponse(jobRepository.save(job));
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private void validateTransition(JobStatus from, JobStatus to) {
        Set<JobStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to))
            throw new IllegalStateException(
                "Transition interdite : " + from + " → " + to);
    }

    private void validateJobRequest(CreateJobRequest req) {
        int sw  = req.getSkillsWeight()        != null ? req.getSkillsWeight()        : 70;
        int pw  = req.getPrerequisitesWeight()  != null ? req.getPrerequisitesWeight()  : 30;
        if (sw + pw != 100)
            throw new IllegalArgumentException(
                "skillsWeight + prerequisitesWeight doit être égal à 100 (obtenu : " + (sw + pw) + ")");

        int tw  = req.getTechnicalSkillWeight() != null ? req.getTechnicalSkillWeight() : 60;
        int sfw = req.getSoftSkillWeight()      != null ? req.getSoftSkillWeight()      : 40;
        if (tw + sfw != 100)
            throw new IllegalArgumentException(
                "technicalSkillWeight + softSkillWeight doit être égal à 100 (obtenu : " + (tw + sfw) + ")");

        if (req.getPrerequisites() == null || req.getPrerequisites().isEmpty())
            throw new IllegalArgumentException("Au moins un prérequis est obligatoire");
        req.getPrerequisites().forEach(p -> {
            if (p.getValue() == null || p.getValue().isBlank())
                throw new IllegalArgumentException("Chaque prérequis doit avoir une valeur non nulle");
        });

        if (req.getTechnicalSkills() == null || req.getTechnicalSkills().isEmpty())
            throw new IllegalArgumentException("Au moins une compétence est obligatoire");

        if (req.getWorkflowStages() != null) {
            req.getWorkflowStages().forEach(stage -> {
                if (stage.getStageType() != null
                        && HUMAN_STAGE_TYPES.contains(stage.getStageType())
                        && (stage.getAssigneeId() == null || stage.getAssigneeId().isBlank()))
                    throw new IllegalArgumentException(
                        "L'étape \"" + stage.getStageType() + "\" doit avoir un responsable assigné (assigneeId)");
            });
        }
    }

    private void applyRequestToJob(Job job, CreateJobRequest req) {
        job.setTitle(req.getTitle());
        job.setDepartment(req.getDepartment());
        job.setLocation(req.getLocation());
        job.setContractType(req.getContractType());
        job.setExperienceLevel(req.getExperienceLevel());
        job.setDescription(req.getDescription());
        job.setOpenPositions(req.getOpenPositions() != null ? req.getOpenPositions() : 1);
        job.setClosingDate(req.getClosingDate());
        job.setIsRemote(req.getIsRemote() != null ? req.getIsRemote() : false);
        job.setSkillsWeight(req.getSkillsWeight() != null ? req.getSkillsWeight() : 70);
        job.setPrerequisitesWeight(req.getPrerequisitesWeight() != null ? req.getPrerequisitesWeight() : 30);
        job.setTechnicalSkillWeight(req.getTechnicalSkillWeight() != null ? req.getTechnicalSkillWeight() : 60);
        job.setSoftSkillWeight(req.getSoftSkillWeight() != null ? req.getSoftSkillWeight() : 40);
    }

    private JobResponse mapToResponse(Job job) {
        JobResponse r = new JobResponse();
        r.setId(job.getId());
        r.setTitle(job.getTitle());
        r.setDepartment(job.getDepartment());
        r.setLocation(job.getLocation());
        r.setContractType(job.getContractType());
        r.setExperienceLevel(job.getExperienceLevel());
        r.setDescription(job.getDescription());
        r.setStatus(job.getStatus());
        r.setCreatedAt(job.getCreatedAt());
        r.setOpenPositions(job.getOpenPositions());
        r.setClosingDate(job.getClosingDate());
        r.setIsRemote(job.getIsRemote());
        r.setSkillsWeight(job.getSkillsWeight() != null ? job.getSkillsWeight() : 70);
        r.setPrerequisitesWeight(job.getPrerequisitesWeight() != null ? job.getPrerequisitesWeight() : 30);
        r.setTechnicalSkillWeight(job.getTechnicalSkillWeight() != null ? job.getTechnicalSkillWeight() : 60);
        r.setSoftSkillWeight(job.getSoftSkillWeight() != null ? job.getSoftSkillWeight() : 40);
        r.setApplicantsCount((int) applicationRepository.countByJobId(job.getId()));

        if (job.getPrerequisites() != null)
            r.setPrerequisites(job.getPrerequisites().stream().map(p -> {
                JobResponse.PrerequisiteDTO d = new JobResponse.PrerequisiteDTO();
                d.setId(p.getId()); d.setType(p.getType()); d.setValue(p.getValue());
                d.setObligatory(p.getObligatory()); d.setIcon(p.getIcon());
                d.setCustomType(p.getCustomType()); d.setWeight(p.getWeight());
                d.setInstruction(p.getInstruction()); d.setJsonSchema(p.getJsonSchema());
                if (p.getOptions() != null && !p.getOptions().isEmpty())
                    d.setOptions(List.of(p.getOptions().split(",")));
                return d;
            }).collect(Collectors.toList()));

        if (job.getTechnicalSkills() != null)
            r.setTechnicalSkills(job.getTechnicalSkills().stream().map(s -> {
                JobResponse.TechnicalSkillDTO d = new JobResponse.TechnicalSkillDTO();
                d.setId(s.getId()); d.setName(s.getName());
                d.setObligatory(s.getObligatory()); d.setWeight(s.getWeight());
                d.setSkillType(s.getSkillType() != null ? s.getSkillType() : "TECHNICAL");
                return d;
            }).collect(Collectors.toList()));

        if (job.getAssessments() != null)
            r.setAssessments(job.getAssessments().stream().map(a -> {
                JobResponse.AssessmentDTO d = new JobResponse.AssessmentDTO();
                d.setId(a.getId());
                d.setName(a.getName());
                d.setType(a.getType());
                d.setDuration(a.getDuration());
                d.setPassingScore(a.getPassingScore());
                d.setAssigneeId(a.getAssigneeId());
                d.setAssigneeName(a.getAssigneeName());
                d.setSubmissionDeadline(a.getSubmissionDeadline());

                d.setLinkId(a.getLinkId());
                return d;
            }).collect(Collectors.toList()));

        if (job.getWorkflowStages() != null)
            r.setWorkflowStages(job.getWorkflowStages().stream().map(w -> {
                JobResponse.WorkflowStageDTO d = new JobResponse.WorkflowStageDTO();
                d.setId(w.getId());
                d.setStageType(w.getStageType());
                d.setName(w.getName());
                d.setDescription(w.getDescription());
                d.setAssignedTo(w.getAssignedTo());
                d.setAssigneeId(w.getAssigneeId());
                d.setOrder(w.getStageOrder());
                d.setAssessmentId(w.getAssessmentId()); // ← lien assessment→stage
                return d;
            }).collect(Collectors.toList()));

        return r;
    }
    public String generateLink(String jobId) {
        // Verify job exists
        jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

        // Generate a short token
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(UUID.randomUUID().toString().getBytes())
                .substring(0, 16);

        return String.format("%s/apply/remote/%s?token=%s", frontendBaseUrl, jobId, token);
    }
    
    
}