package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Persiste chaque événement de triche détecté côté frontend.
 */
@Entity
@Table(name = "anti_cheat_events")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AntiCheatEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** ID du test (Assessment.id) */
    @Column(nullable = false)
    private String testId;

    /** ID de session (TestSession.id — unified RH/TECHNICAL session) */
    @Column(nullable = false)
    private String sessionId;

    /**
     * Type d'événement :
     * TAB_SWITCH | WINDOW_BLUR | PASTE | COPY | DEVTOOLS | RIGHT_CLICK
     */
    @Column(nullable = false, length = 50)
    private String type;

    /** Détail lisible (texte collé tronqué, raccourci utilisé, etc.) */
    @Column(length = 500)
    private String detail;

    /** Index (0-based) de la question courante au moment de l'événement */
    private int questionIndex;

    /** Horodatage exact de l'événement (UTC) */
    @Column(nullable = false)
    private LocalDateTime occurredAt;
}