package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
    name = "test_session_answers",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_session_answer_question",
        columnNames = {"session_id", "question_id"}
    )
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(of = "id")
public class TestSessionAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private TestSession session;

    @Column(name = "question_id", nullable = false)
    private String questionId;

    // ── QCM / RH: selected option IDs (RADIO = 1 entry, CHECKBOX = many) ──────

    @ElementCollection
    @CollectionTable(
        name = "test_session_answer_options",
        joinColumns = @JoinColumn(name = "answer_id")
    )
    @Column(name = "option_id")
    @Builder.Default
    private List<String> selectedOptionIds = new ArrayList<>();

    // ── LIKERT: 1-5 integer value ─────────────────────────────────────────────

    @Column(name = "likert_value")
    private Integer likertValue;

    // ── PROBLEM_SOLVING: submitted code ──────────────────────────────────────

    @Column(name = "submitted_code", columnDefinition = "TEXT")
    private String submittedCode;

    @Column(name = "submitted_language", length = 50)
    private String submittedLanguage;

    // ── PROBLEM_SOLVING: per-test-case execution results ─────────────────────

    @OneToMany(mappedBy = "sessionAnswer", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("caseIndex ASC")
    @Builder.Default
    private List<TestCaseResult> testCaseResults = new ArrayList<>();
}
