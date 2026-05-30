package com.nexgenai.model;

import com.nexgenai.model.enums.NotificationType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "notifications", indexes = {
    @Index(name = "idx_notif_user_id",   columnList = "user_id"),
    @Index(name = "idx_notif_user_read", columnList = "user_id, read_flag")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "related_entity_id")
    private String relatedEntityId;

    @Column(name = "related_entity_type")
    private String relatedEntityType;

    private String link;

    @Column(name = "read_flag", nullable = false)
    @Builder.Default
    private boolean read = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
