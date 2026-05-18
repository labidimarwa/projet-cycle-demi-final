package com.nexgenai.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "prerequisites")
@ToString(exclude = "job")  
public class Prerequisite {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    
    private String type;
    private String value;
    private Boolean obligatory;
    private String icon;
    private Boolean customType;
    
    @Column(columnDefinition = "TEXT")
    private String options;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id")
    @JsonIgnore
    // ← NE PAS mettre @ToString.Exclude ici
    private Job job;
}