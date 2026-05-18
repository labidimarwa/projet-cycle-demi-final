package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Link between a {@link TestTheme} and a {@link PsychometricModel}, with
 * its own weight and ordered set of {@link Question}s.
 */
@Entity
@Table(name = "theme_models")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(exclude = {"theme", "questions"})
public class ThemeModel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "theme_id", nullable = false)
    private TestTheme theme;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "model_id", nullable = false)
    private PsychometricModel model;

    private Integer weight;
    private Integer orderIndex;

    @OneToMany(mappedBy = "themeModel", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    @Builder.Default
    private Set<Question> questions = new LinkedHashSet<>();

    public void addQuestion(Question q) {
        if (questions == null) questions = new LinkedHashSet<>();
        questions.add(q);
        q.setThemeModel(this);
    }

    public Set<Question> getQuestions() {
        if (questions == null) questions = new LinkedHashSet<>();
        return questions;
    }
}
