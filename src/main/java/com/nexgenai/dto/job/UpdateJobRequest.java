package com.nexgenai.dto.job;  // ← Ajoutez cette ligne

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.nexgenai.model.enums.AssessmentType;
import com.nexgenai.model.enums.JobStatus;
import com.nexgenai.model.enums.StageType;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.nexgenai.model.enums.AssessmentType;
import com.nexgenai.model.enums.JobStatus;
import com.nexgenai.model.enums.StageType;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateJobRequest {

    private String    title;
    private String    department;
    private String    location;
    private String    contractType;
    private String    experienceLevel;
    private String    description;
    private Integer   openPositions;
    private LocalDate closingDate;
    private Boolean   isRemote;
    private JobStatus status;

    private List<PrerequisiteDTO>   prerequisites;
    private List<TechnicalSkillDTO> technicalSkills;
    private List<AssessmentDTO>     assessments;
    private List<WorkflowStageDTO>  workflowStages;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PrerequisiteDTO {
        private String       type;
        private String       value;
        private Boolean      obligatory;
        private String       icon;
        private Boolean      customType;
        private List<String> options;
        private Integer      weight;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)   // ← ignore "id"
    public static class TechnicalSkillDTO {
        private String  name;
        private Boolean obligatory;
        private Integer weight;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)   // ← ignore "id"
    public static class AssessmentDTO {
        private String         name;
        private AssessmentType type;
        private Integer        duration;
        private Integer        passingScore;
        private String         assigneeId;
        private String         assigneeName;
        private String         linkId;
        private LocalDate submissionDeadline;

    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)   // ← ignore "id"
    public static class WorkflowStageDTO {
        private StageType stageType;
        private String    name;
        private String    description;
        private String    assignedTo;
        private String    assigneeId;
        private Integer   order;
        private String    assessmentId;
    }
}