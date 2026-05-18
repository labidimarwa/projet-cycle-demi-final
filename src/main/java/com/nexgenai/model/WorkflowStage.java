package com.nexgenai.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nexgenai.model.enums.StageType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "workflow_stages")
@ToString(exclude = "job")
public class WorkflowStage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String name;
    private String description;
    private String assignedTo;   // display name

    /** The actual user/evaluator ID assigned to this stage */
    @Column(name = "assignee_id")
    private String assigneeId;

    /** Semantic stage type for routing / display */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage_type")
    private StageType stageType;

    @Column(name = "stage_order")
    private Integer stageOrder;

    /**
     * Optional link to the Assessment that generated this stage.
     * Null for manually added stages (AI_SCREENING, RH_INTERVIEW, etc.)
     */
    @Column(name = "assessment_id")
    private String assessmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id")
    @JsonIgnore
    private Job job;
}