package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.*;
import java.util.*;

@Entity
@Table(name = "psychometric_models")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"dimensions"})
public class PsychometricModel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    private ScoringType scoringType;

    private boolean builtIn;

    @OneToMany(mappedBy = "model", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    @Builder.Default  // ← Ajoutez cette annotation
    private List<ModelDimension> dimensions = new ArrayList<>();

    public enum ScoringType { SUM, AVERAGE, WEIGHTED, IPSATIVE }

    public void addDimension(ModelDimension d) {
        dimensions.add(d);
        d.setModel(this);
    }
}