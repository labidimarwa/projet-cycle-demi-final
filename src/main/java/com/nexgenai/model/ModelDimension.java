package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "model_dimensions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "model")
public class ModelDimension {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_id", nullable = false)
    private PsychometricModel model;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 10)
    private String code;

    @Column(length = 20)
    private String color;   // hex e.g. "#ef4444"

    private Integer orderIndex;
}