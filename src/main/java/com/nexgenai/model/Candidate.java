package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@DiscriminatorValue("CANDIDATE")
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class Candidate extends User {
    

    
    @Column(name = "city")
    private String city;
    

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;
    
    @Column(name = "current_position")
    private String currentPosition;

    @Column(name = "links_json", length = 2000)
    private String linksJson;
    @Column(name = "years_of_experience")
    private Integer yearsOfExperience;
    
    @Column(name = "education_level")
    private String educationLevel;
    
    
    @Column(name = "university")
    private String university;
    

    @Column(name = "cv_path", length = 500)
    private String cvPath;
    
    @Column(name = "linkedin_url", length = 500)
    private String linkedinUrl;
    
    @Column(name = "github_url", length = 500)
    private String githubUrl;
    
    @Column(name = "portfolio_url", length = 500)
    private String portfolioUrl;
    


    
    @Column(name = "remote_work_preference")
    private String remoteWorkPreference; // FULL_REMOTE, HYBRID, OFFICE

    

    @Column(name = "certifications", length = 1000)
    private String certifications;
    
    @Column(name = "summary", length = 2000)
    private String summary;
    
    @Override
    @Transient
    public String getUserType() {
        return "CANDIDATE";
    }
}