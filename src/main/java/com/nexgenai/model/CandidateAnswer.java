package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "candidate_answers")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"submission", "question"})
public class CandidateAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private TestSubmission submission;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    // IDs des options sélectionnées (stockés en JSON ou CSV)
    @ElementCollection
    @CollectionTable(
        name = "candidate_answer_options",
        joinColumns = @JoinColumn(name = "answer_id")
    )
    @Column(name = "option_id")
    @Builder.Default
    private List<String> selectedOptionIds = new ArrayList<>();

    // Pour les questions ouvertes / code
    @Column(name = "text_answer", columnDefinition = "TEXT")
    private String textAnswer;

    @Column(name = "earned_points")
    private Integer earnedPoints;
}