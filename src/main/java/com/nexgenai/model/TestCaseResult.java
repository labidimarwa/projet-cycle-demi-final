package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "test_case_results")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(of = "id")
public class TestCaseResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "answer_id", nullable = false)
    private TestSessionAnswer sessionAnswer;

    @Column(name = "case_index", nullable = false)
    private int caseIndex;

    @Column(columnDefinition = "TEXT")
    private String input;

    @Column(name = "expected_output", columnDefinition = "TEXT")
    private String expectedOutput;

    @Column(name = "actual_output", columnDefinition = "TEXT")
    private String actualOutput;

    private boolean passed;

    private int points;

    @Column(name = "earned_points")
    private int earnedPoints;

    @Column(name = "execution_ms")
    private Long executionMs;

    @Column(name = "is_visible")
    private boolean isVisible;

    @Column(columnDefinition = "TEXT")
    private String error;
}
