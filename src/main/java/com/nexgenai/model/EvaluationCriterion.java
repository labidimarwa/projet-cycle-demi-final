package com.nexgenai.model;
 
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nexgenai.model.enums.CriterionType;
import jakarta.persistence.*;
import lombok.*;
 
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "evaluation_criteria")
@ToString(exclude = "interview")
public class EvaluationCriterion {
 
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
 
    @Column(nullable = false)
    private String name;
 
    @Column(columnDefinition = "TEXT")
    private String description;
 
    /**
     * SCORE_1_5 | SCORE_1_10 | BOOLEAN | GRADE (A-F) | STARS (1-5)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "criterion_type")
    private CriterionType criterionType = CriterionType.SCORE_1_10;
 
    /** Weight used for weighted average (default 1) */
    private Integer weight = 1;
 
    /** Is this criterion mandatory to fill? */
    private Boolean mandatory = true;
 
    @Column(name = "criterion_order")
    private Integer criterionOrder = 0;
 
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interview_id")
    @JsonIgnore
    private Interview interview;
}
 