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

    /** Poids de la section Compétences dans le score global (0–100, défaut 70). */
    @Column(name = "skills_weight")
    private Integer skillsWeight = 70;

    /** Poids de la section Prérequis dans le score global (0–100, défaut 30). */
    @Column(name = "prerequisites_weight")
    private Integer prerequisitesWeight = 30;

    /** Poids des compétences techniques dans la section Skills (défaut 60). */
    @Column(name = "technical_skill_weight")
    private Integer technicalSkillWeight = 60;

    /** Poids des compétences soft dans la section Skills (défaut 40). */
    @Column(name = "soft_skill_weight")
    private Integer softSkillWeight = 40;

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
        if (skillsWeight == null) skillsWeight = 70;
        if (prerequisitesWeight == null) prerequisitesWeight = 30;
        if (technicalSkillWeight == null) technicalSkillWeight = 60;
        if (softSkillWeight == null) softSkillWeight = 40;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}