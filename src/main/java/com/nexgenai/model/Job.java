package com.nexgenai.model;

import com.nexgenai.model.enums.*;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "jobs")
@ToString(exclude = {"prerequisites", "technicalSkills", "assessments", "workflowStages"})
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String title;

    private String department;
    private String location;

    @Enumerated(EnumType.STRING)
    private ContractType contractType;

    @Enumerated(EnumType.STRING)
    private ExperienceLevel experienceLevel;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    private JobStatus status;

    // ── NEW FIELDS ────────────────────────────────────────────────────────────

    @Column(name = "open_positions")
    private Integer openPositions = 1;

    @Column(name = "closing_date")
    private LocalDate closingDate;

    @Column(name = "is_remote")
    private Boolean isRemote = false;

    // ── TIMESTAMPS ────────────────────────────────────────────────────────────

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ── RELATIONS ─────────────────────────────────────────────────────────────

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Prerequisite> prerequisites = new ArrayList<>();

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TechnicalSkill> technicalSkills = new ArrayList<>();

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Assessment> assessments = new ArrayList<>();

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorkflowStage> workflowStages = new ArrayList<>();

    // ── HELPERS ───────────────────────────────────────────────────────────────

    public void addPrerequisite(Prerequisite prerequisite) {
        prerequisites.add(prerequisite);
        prerequisite.setJob(this);
    }

    public void addTechnicalSkill(TechnicalSkill skill) {
        technicalSkills.add(skill);
        skill.setJob(this);
    }

    public void addAssessment(Assessment assessment) {
        assessments.add(assessment);
        assessment.setJob(this);
    }

    public void addWorkflowStage(WorkflowStage stage) {
        workflowStages.add(stage);
        stage.setJob(this);
    }

    // ── LIFECYCLE ─────────────────────────────────────────────────────────────

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = JobStatus.DRAFT;
        if (openPositions == null) openPositions = 1;
        if (isRemote == null) isRemote = false;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}