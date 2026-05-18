package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Entity-backed option for an RH psychometric {@link Question}. Each option
 * references a {@code ModelDimension} so that the scoring engine can
 * accumulate dimension-level points.
 *
 * TECHNICAL QCM questions use the lighter JSON-backed
 * {@link Question.QcmOption} instead.
 */
@Entity
@Table(name = "question_options")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "question")
@EqualsAndHashCode(exclude = "question")
public class QuestionOption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String text;

    /** References {@code ModelDimension.id}. */
    private String dimensionId;

    private Integer points;

    private Integer orderIndex;
}
