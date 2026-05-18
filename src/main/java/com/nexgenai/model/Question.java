package com.nexgenai.model;

import com.nexgenai.config.IntegerListConverter;
import com.nexgenai.config.QcmOptionListConverter;
import com.nexgenai.config.TestCaseListConverter;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified question entity.
 *
 * Merges the previous {@code TestQuestion} (psychometric questions linked
 * to a {@code ThemeModel} with entity-backed {@link QuestionOption}s) and
 * {@code SimpleQuestion} (technical questions linked to a {@link TestTheme}
 * with JSON-serialised options and test-cases).
 *
 * <p>The {@link QuestionKind} field distinguishes the two flavours:
 * <ul>
 *   <li>{@link QuestionKind#QCM} — multiple-choice question
 *       (RADIO / CHECKBOX / LIKERT / RANKING) — used by both RH and
 *       TECHNICAL assessments.</li>
 *   <li>{@link QuestionKind#PROBLEM_SOLVING} — coding problem with test
 *       cases — TECHNICAL only.</li>
 * </ul>
 *
 * Phase 1 of the backend refactoring – core models.
 */
@Entity
@Table(name = "questions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(exclude = {"assessment", "theme", "themeModel", "options"})
public class Question {

    // ── Identity ──────────────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** PROBLEM_SOLVING | QCM. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private QuestionKind kind;

    /** Sub-type for QCM: RADIO | CHECKBOX | LIKERT | RANKING. */
    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", length = 20)
    private QuestionType questionType;

    // ── Common content ────────────────────────────────────────────────────────

    /** Short title (technical) — optional for RH psychometric questions. */
    private String title;

    /** Primary statement / wording of the question. */
    @Column(name = "text", columnDefinition = "TEXT")
    private String text;

    /** Long-form statement (alias of {@code text} for problem-solving). */
    @Column(name = "statement", columnDefinition = "TEXT")
    private String statement;

    @Column(name = "image_path")
    private String imagePath;

    /** Total points awarded by this question. */
    private Integer points;

    @Column(name = "order_index")
    private Integer orderIndex;

    // ── Relationships ─────────────────────────────────────────────────────────

    /**
     * Parent assessment (used by TECHNICAL questions attached directly to
     * the assessment, and as a denormalised fast-lookup for RH ones).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id")
    private Assessment assessment;

    /**
     * Parent theme (used primarily by TECHNICAL QCM / PROBLEM_SOLVING
     * questions that group under a {@link TestTheme}).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "theme_id")
    private TestTheme theme;

    /**
     * Parent theme-model (used by RH psychometric questions which hang
     * underneath a {@link ThemeModel}).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "theme_model_id")
    private ThemeModel themeModel;

    /**
     * Entity-backed options — used by RH psychometric questions where each
     * option points to a {@code ModelDimension} for dimension-level scoring.
     */
    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    @Builder.Default
    private List<QuestionOption> options = new ArrayList<>();

    // ── Problem-Solving fields ────────────────────────────────────────────────

    /** Target complexity hint: O(n) | O(n log n) | O(n²) ... */
    @Column(length = 30)
    private String complexity;

    /** Execution time limit in seconds. */
    @Column(name = "time_limit")
    private Double timeLimit;

    /** Memory limit in MB. */
    @Column(name = "memory_limit")
    private Integer memoryLimit;

    /**
     * Test cases for a PROBLEM_SOLVING question. Serialised as JSON.
     */
    @Convert(converter = TestCaseListConverter.class)
    @Column(name = "test_cases", columnDefinition = "TEXT")
    private List<TestCase> testCases;

    // ── QCM (JSON-backed) fields ──────────────────────────────────────────────

    /**
     * JSON-backed QCM options — used by TECHNICAL QCM questions which do
     * not need dimension-level scoring. RH questions use {@link #options}.
     */
    @Convert(converter = QcmOptionListConverter.class)
    @Column(name = "qcm_options", columnDefinition = "TEXT")
    private List<QcmOption> qcmOptions;

    /**
     * Points awarded per Likert value (index 0–4 → values 1–5). Serialised
     * as JSON.
     */
    @Convert(converter = IntegerListConverter.class)
    @Column(name = "likert_points", columnDefinition = "TEXT")
    private List<Integer> likertPoints;

    // ── Helpers ───────────────────────────────────────────────────────────────

    public List<QuestionOption> getOptions() {
        if (options == null) options = new ArrayList<>();
        return options;
    }

    public void addOption(QuestionOption opt) {
        getOptions().add(opt);
        opt.setQuestion(this);
    }

    // ── Nested types ──────────────────────────────────────────────────────────

    /** Top-level question kind. */
    public enum QuestionKind {
        QCM,
        PROBLEM_SOLVING
    }

    /** Specialised sub-type for QCM questions. */
    public enum QuestionType {
        RADIO, CHECKBOX, LIKERT, RANKING
    }

    /**
     * Inline QCM option used by TECHNICAL questions (JSON-backed).
     * RH questions use the entity-backed {@link QuestionOption} instead.
     */
    @lombok.Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QcmOption {
        private String  id;
        private String  text;
        private boolean correct;
        private Integer points;

        public QcmOption(String text, boolean correct, Integer points) {
            this.text = text;
            this.correct = correct;
            this.points = points;
        }
    }

    /** Test-case for a PROBLEM_SOLVING question (JSON-backed). */
    @lombok.Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestCase {
        private String  input;
        private String  output;
        private Integer points;
        private boolean visible;
    }
}
