package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
    name = "test_session_answer_decisions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_decision_session_question",
        columnNames = {"session_id", "question_id"}
    )
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(of = "id")
public class TestSessionAnswerDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private TestSession session;

    @Column(name = "question_id", nullable = false)
    private String questionId;

    @Column(length = 20)
    private String decision; // APPROVED | REJECTED

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "manual_points")
    private Integer manualPoints;
}
