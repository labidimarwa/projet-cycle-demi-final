package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Theme grouping inside an {@link Assessment}.
 *
 * Previously linked to {@code JobTest}; after the Phase 1 refactor the
 * back-reference is to the unified {@link Assessment} entity.
 */
@Entity
@Table(name = "test_themes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString(exclude = {"assessment", "themeModels"})
public class TestTheme {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", nullable = false)
    private Assessment assessment;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    private ThemeCategory category;

    private Integer orderIndex;

    @OneToMany(mappedBy = "theme", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    @Builder.Default
    private Set<ThemeModel> themeModels = new LinkedHashSet<>();

    public enum ThemeCategory {
        PERSONALITY, MOTIVATION, BEHAVIOR, LOGIC, EMOTIONAL_INTELLIGENCE, CUSTOM
    }

    public void addThemeModel(ThemeModel tm) {
        if (themeModels == null) themeModels = new LinkedHashSet<>();
        themeModels.add(tm);
        tm.setTheme(this);
    }

    public Set<ThemeModel> getThemeModels() {
        if (themeModels == null) themeModels = new LinkedHashSet<>();
        return themeModels;
    }
}
