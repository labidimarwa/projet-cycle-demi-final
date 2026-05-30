package com.nexgenai.dto.job;

import com.nexgenai.dto.assessment.AssessmentDto;
import com.nexgenai.model.enums.AssessmentType;
import com.nexgenai.model.enums.ContractType;
import com.nexgenai.model.enums.ExperienceLevel;
import com.nexgenai.model.enums.JobStatus;
import com.nexgenai.model.enums.StageType;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
@Data
public class JobResponse {

    private String          id;
    private String          title;
    private String          department;
    private String          location;
    private ContractType    contractType;
    private ExperienceLevel experienceLevel;
    private String          description;
    private JobStatus       status;
    private LocalDateTime   createdAt;
    private LocalDate submissionDeadline;


    // ── NEW FIELDS ────────────────────────────────────────────────────────────
    private Integer   openPositions;
    private LocalDate closingDate;
    private Boolean   isRemote;

    // ── MATCHING WEIGHTS ──────────────────────────────────────────────────────
    private Integer skillsWeight         = 70;
    private Integer prerequisitesWeight  = 30;
    private Integer technicalSkillWeight = 60;
    private Integer softSkillWeight      = 40;

    // ── COMPUTED ──────────────────────────────────────────────────────────────
    private Integer applicantsCount = 0;
    private Integer avgMatchScore   = 0;

    // ── COLLECTIONS ───────────────────────────────────────────────────────────
    private List<PrerequisiteDTO>   prerequisites;
    private List<TechnicalSkillDTO> technicalSkills;
    private List<AssessmentDTO>     assessments;
    private List<WorkflowStageDTO>  workflowStages;

    // ── Getters / Setters ─────────────────────────────────────────────────────
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDepartment() { return department; }
    public void setDepartment(String d) { this.department = d; }
    public String getLocation() { return location; }
    public void setLocation(String l) { this.location = l; }
    public ContractType getContractType() { return contractType; }
    public void setContractType(ContractType c) { this.contractType = c; }
    public ExperienceLevel getExperienceLevel() { return experienceLevel; }
    public void setExperienceLevel(ExperienceLevel e) { this.experienceLevel = e; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus s) { this.status = s; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime c) { this.createdAt = c; }
    public Integer getOpenPositions() { return openPositions; }
    public void setOpenPositions(Integer o) { this.openPositions = o; }
    public LocalDate getClosingDate() { return closingDate; }
    public void setClosingDate(LocalDate c) { this.closingDate = c; }
    public Boolean getIsRemote() { return isRemote; }
    public void setIsRemote(Boolean r) { this.isRemote = r; }
    public Integer getSkillsWeight() { return skillsWeight; }
    public void setSkillsWeight(Integer w) { this.skillsWeight = w; }
    public Integer getPrerequisitesWeight() { return prerequisitesWeight; }
    public void setPrerequisitesWeight(Integer w) { this.prerequisitesWeight = w; }
    public Integer getTechnicalSkillWeight() { return technicalSkillWeight; }
    public void setTechnicalSkillWeight(Integer w) { this.technicalSkillWeight = w; }
    public Integer getSoftSkillWeight() { return softSkillWeight; }
    public void setSoftSkillWeight(Integer w) { this.softSkillWeight = w; }
    public Integer getApplicantsCount() { return applicantsCount; }
    public void setApplicantsCount(Integer a) { this.applicantsCount = a; }
    public Integer getAvgMatchScore() { return avgMatchScore; }
    public void setAvgMatchScore(Integer a) { this.avgMatchScore = a; }
    public List<PrerequisiteDTO> getPrerequisites() { return prerequisites; }
    public void setPrerequisites(List<PrerequisiteDTO> p) { this.prerequisites = p; }
    public List<TechnicalSkillDTO> getTechnicalSkills() { return technicalSkills; }
    public void setTechnicalSkills(List<TechnicalSkillDTO> t) { this.technicalSkills = t; }
    public List<AssessmentDTO> getAssessments() { return assessments; }
    public void setAssessments(List<AssessmentDTO> a) { this.assessments = a; }
    public List<WorkflowStageDTO> getWorkflowStages() { return workflowStages; }
    public void setWorkflowStages(List<WorkflowStageDTO> w) { this.workflowStages = w; }

    // ── Nested DTOs ───────────────────────────────────────────────────────────

    public static class PrerequisiteDTO {
        private String id, type, value, icon;
        private Boolean obligatory, customType;
        private List<String> options;
        private Integer weight;
        private String instruction;
        private String jsonSchema;
        public String getId() { return id; } public void setId(String id) { this.id = id; }
        public String getType() { return type; } public void setType(String t) { this.type = t; }
        public String getValue() { return value; } public void setValue(String v) { this.value = v; }
        public Boolean getObligatory() { return obligatory; } public void setObligatory(Boolean o) { this.obligatory = o; }
        public String getIcon() { return icon; } public void setIcon(String i) { this.icon = i; }
        public Boolean getCustomType() { return customType; } public void setCustomType(Boolean c) { this.customType = c; }
        public List<String> getOptions() { return options; } public void setOptions(List<String> o) { this.options = o; }
        public Integer getWeight() { return weight; } public void setWeight(Integer w) { this.weight = w; }
        public String getInstruction() { return instruction; } public void setInstruction(String i) { this.instruction = i; }
        public String getJsonSchema() { return jsonSchema; } public void setJsonSchema(String j) { this.jsonSchema = j; }
    }

    public static class TechnicalSkillDTO {
        private String id, name, skillType;
        private Integer weight;
        private Boolean obligatory;
        public String getId() { return id; } public void setId(String id) { this.id = id; }
        public String getName() { return name; } public void setName(String n) { this.name = n; }
        public Boolean getObligatory() { return obligatory; } public void setObligatory(Boolean o) { this.obligatory = o; }
        public Integer getWeight() { return weight; } public void setWeight(Integer w) { this.weight = w; }
        public String getSkillType() { return skillType; } public void setSkillType(String t) { this.skillType = t; }
    }
    @Data

    public static class AssessmentDTO {
        private String  id, name, assigneeId, assigneeName, linkId;
        private AssessmentType type;
        private Integer duration, passingScore;
        private LocalDate submissionDeadline;
        public String getId() { return id; } public void setId(String id) { this.id = id; }
        public String getName() { return name; } public void setName(String n) { this.name = n; }
        public AssessmentType getType() { return type; } public void setType(AssessmentType t) { this.type = t; }
        public Integer getDuration() { return duration; } public void setDuration(Integer d) { this.duration = d; }
        public Integer getPassingScore() { return passingScore; } public void setPassingScore(Integer p) { this.passingScore = p; }
        public String getAssigneeId() { return assigneeId; } public void setAssigneeId(String a) { this.assigneeId = a; }
        public String getAssigneeName() { return assigneeName; } public void setAssigneeName(String a) { this.assigneeName = a; }
        public String getLinkId() { return linkId; } public void setLinkId(String l) { this.linkId = l; }
    }

    public static class WorkflowStageDTO {
        private String id, name, description, assignedTo, assigneeId, assessmentId;
        private Integer order;
        private StageType stageType;

        public String getId() { return id; } public void setId(String id) { this.id = id; }
        public String getName() { return name; } public void setName(String n) { this.name = n; }
        public String getDescription() { return description; } public void setDescription(String d) { this.description = d; }
        public String getAssignedTo() { return assignedTo; } public void setAssignedTo(String a) { this.assignedTo = a; }
        public Integer getOrder() { return order; } public void setOrder(Integer o) { this.order = o; }
        public StageType getStageType() { return stageType; } public void setStageType(StageType s) { this.stageType = s; }
        public String getAssigneeId() { return assigneeId; } public void setAssigneeId(String a) { this.assigneeId = a; }

        /** Links this stage to its source Assessment (null if manually added). */
        public String getAssessmentId() { return assessmentId; }
        public void setAssessmentId(String a) { this.assessmentId = a; }
    }
}