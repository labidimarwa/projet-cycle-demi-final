package com.nexgenai.service;

import com.nexgenai.dto.job.CreateJobRequest;
import com.nexgenai.dto.job.JobResponse;
import com.nexgenai.dto.job.UpdateJobRequest;
import com.nexgenai.model.*;
import com.nexgenai.model.enums.ContractType;
import com.nexgenai.model.enums.ExperienceLevel;
import com.nexgenai.model.enums.JobStatus;
import com.nexgenai.repository.ApplicationRepository;
import com.nexgenai.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class JobService {

    private final JobRepository         jobRepository;
    private final ApplicationRepository applicationRepository;
    private final InterviewService interviewService;

    public JobService(JobRepository jobRepository, ApplicationRepository applicationRepository, InterviewService interviewService) {
        this.jobRepository         = jobRepository;
        this.applicationRepository = applicationRepository;
        this.interviewService= interviewService;
        
    }

    // ── CREATE ────────────────────────────────────────────────────────────────
    @Transactional
    public JobResponse createJob(CreateJobRequest req) {
        Job job = new Job();
        applyRequestToJob(job, req);
        job.setStatus(JobStatus.ACTIVE);

        if (req.getPrerequisites() != null) {
            req.getPrerequisites().forEach(p -> {
                Prerequisite prereq = new Prerequisite();
                prereq.setType(p.getType());
                prereq.setValue(p.getValue());
                prereq.setObligatory(p.getObligatory());
                prereq.setIcon(p.getIcon());
                prereq.setCustomType(p.getCustomType());
                if (p.getOptions() != null)
                    prereq.setOptions(String.join(",", p.getOptions()));
                job.addPrerequisite(prereq);
            });
        }

        if (req.getTechnicalSkills() != null) {
            req.getTechnicalSkills().forEach(s -> {
                TechnicalSkill skill = new TechnicalSkill();
                skill.setName(s.getName());
                skill.setObligatory(s.getObligatory());
                skill.setWeight(s.getWeight());
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

        // ── Prerequisites ─────────────────────────────────────────────────────
        if (req.getPrerequisites() != null) {
            job.getPrerequisites().clear();
            req.getPrerequisites().forEach(p -> {
                Prerequisite prereq = new Prerequisite();
                prereq.setType(p.getType());
                prereq.setValue(p.getValue());
                prereq.setObligatory(p.getObligatory());
                prereq.setIcon(p.getIcon());
                prereq.setCustomType(p.getCustomType());
                if (p.getOptions() != null)
                    prereq.setOptions(String.join(",", p.getOptions()));
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
        // (même logique que createJob — on repart de zéro pour éviter les doublons)
        if (interviewsNeedRebuild) {
            interviewService.deleteInterviewsForJob(saved);   // ← à implémenter si pas déjà là
            interviewService.createInterviewsForJob(saved);
        }

        return mapToResponse(saved);
    }
    
    // ── STATUS CHANGE ─────────────────────────────────────────────────────────
    @Transactional
    public JobResponse changeStatus(String id, JobStatus newStatus) {
        Job job = jobRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Job not found: " + id));
        job.setStatus(newStatus);
        return mapToResponse(jobRepository.save(job));
    }

    // ── DELETE ────────────────────────────────────────────────────────────────
    @Transactional
    public void deleteJob(String id) {
        if (!jobRepository.existsById(id))
            throw new RuntimeException("Job not found: " + id);
        jobRepository.deleteById(id);
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
        r.setApplicantsCount((int) applicationRepository.countByJobId(job.getId()));

        if (job.getPrerequisites() != null)
            r.setPrerequisites(job.getPrerequisites().stream().map(p -> {
                JobResponse.PrerequisiteDTO d = new JobResponse.PrerequisiteDTO();
                d.setId(p.getId()); d.setType(p.getType()); d.setValue(p.getValue());
                d.setObligatory(p.getObligatory()); d.setIcon(p.getIcon());
                d.setCustomType(p.getCustomType());
                if (p.getOptions() != null && !p.getOptions().isEmpty())
                    d.setOptions(List.of(p.getOptions().split(",")));
                return d;
            }).collect(Collectors.toList()));

        if (job.getTechnicalSkills() != null)
            r.setTechnicalSkills(job.getTechnicalSkills().stream().map(s -> {
                JobResponse.TechnicalSkillDTO d = new JobResponse.TechnicalSkillDTO();
                d.setId(s.getId()); d.setName(s.getName());
                d.setObligatory(s.getObligatory()); d.setWeight(s.getWeight());
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
    
    
    
}