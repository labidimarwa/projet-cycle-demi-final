package com.nexgenai.dto.job;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.nexgenai.model.enums.AssessmentType;
import com.nexgenai.model.enums.ContractType;
import com.nexgenai.model.enums.ExperienceLevel;
import com.nexgenai.model.enums.StageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateJobRequest {

    @NotBlank
    @Size(min = 3, max = 100)
    private String title;

    private String department;
    private String location;

    @NotNull
    private ContractType contractType;

    @NotNull
    private ExperienceLevel experienceLevel;

    private String description;

    private Integer   openPositions = 1;
    private LocalDate closingDate;

    private Boolean   isRemote      = false;

    private List<PrerequisiteDTO>   prerequisites;
    private List<TechnicalSkillDTO> technicalSkills;
    private List<AssessmentDTO>     assessments;
    private List<WorkflowStageDTO>  workflowStages;

    // ─── Nested DTOs ──────────────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PrerequisiteDTO {
        private String       id;
        private String       type;
        private String       value;
        private Boolean      obligatory;
        private String       icon;
        private Boolean      customType;
        private List<String> options;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TechnicalSkillDTO {
        private String  id;
        private String  name;
        private Integer minLevel;
        private Boolean obligatory;
        private Integer weight;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssessmentDTO {
        private String         id;
        private String         name;
        private AssessmentType type;
        private Integer        duration;
        private Integer        passingScore;
        private String         assigneeId;
        private String         assigneeName;
        private String         linkId;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)   // ← dragOver et tout champ UI sont ignorés
    public static class WorkflowStageDTO {
        private String    id;
        private StageType stageType;
        private String    name;
        private String    description;
        private String    assignedTo;
        private String    assigneeId;
        private Integer   order;
        private String    assessmentId;
    }
}