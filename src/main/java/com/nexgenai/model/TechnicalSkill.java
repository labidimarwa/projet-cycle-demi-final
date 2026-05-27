package com.nexgenai.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
@Entity

@Data  // ← Ajoutez ceci pour générer getters, setters, toString, etc.
@NoArgsConstructor  // ← Constructeur sans argument
@AllArgsConstructor // ← Constructeur avec tous les arguments
@Table(name = "technical_skills")
@ToString(exclude = "job")  
public class TechnicalSkill {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    
    private String name;
    private Boolean obligatory;
    private Integer weight;

    /** "TECHNICAL" or "SOFT" — default TECHNICAL for backward compat. */
    @Column(name = "skill_type")
    private String skillType = "TECHNICAL";
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id")
    @JsonIgnore
    private Job job;
    
    // Getters et Setters
}