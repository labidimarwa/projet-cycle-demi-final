package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@Entity
@DiscriminatorValue("TECH_EVALUATOR")
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TechEvaluator extends User {
    
    @Column(name = "specialization")
    private String specialization; // DEVELOPER, ACCOUNTANT, ENGINEER, etc.
    
    @Column(name = "title")
    private String title;
    
    @Column(name = "employee_id")
    private String employeeId;
    
    @Column(name = "years_of_experience")
    private Integer yearsOfExperience;
    
    @Column(name = "expertise_level")
    private String expertiseLevel; // JUNIOR, MID, SENIOR, LEAD, ARCHITECT
    
    @Column(name = "current_company")
    private String currentCompany;



    @Column(name = "max_evaluations_per_day")
    private Integer maxEvaluationsPerDay = 3;
    
    @Column(name = "evaluations_today")
    private Integer evaluationsToday = 0;
    
    @Column(name = "average_rating")
    private Double averageRating;
    
    @Column(name = "total_evaluations")
    private Integer totalEvaluations = 0;
    
    @Column(name = "linkedin_url")
    private String linkedinUrl;
    
    @Column(name = "github_url")
    private String githubUrl;
    
    @Column(name = "can_create_technical_tests")
    private boolean canCreateTechnicalTests = true;
    
    @Column(name = "can_grade_tests")
    private boolean canGradeTests = true;
    
    @Column(name = "can_conduct_interviews")
    private boolean canConductInterviews = true;
    
    @Override
    @Transient
    public String getUserType() {
        return "TECH_EVALUATOR";
    }
}