package com.nexgenai.service;

import com.nexgenai.dto.notification.NotificationDto;
import com.nexgenai.model.Notification;
import com.nexgenai.model.enums.NotificationType;
import com.nexgenai.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository repo;
    private final SseEmitterRegistry     registry;

    // ── Send ─────────────────────────────────────────────────────────────────

    @Transactional
    public void send(String userId, NotificationType type,
                     String title, String message,
                     String relatedId, String relatedType, String link) {
        if (userId == null) return;

        Notification notif = Notification.builder()
                .userId(userId)
                .type(type)
                .title(title)
                .message(message)
                .relatedEntityId(relatedId)
                .relatedEntityType(relatedType)
                .link(link)
                .read(false)
                .createdAt(Instant.now())
                .build();

        Notification saved = repo.save(notif);
        registry.sendToUser(userId, toDto(saved));
        log.debug("Notification sent → userId={} type={}", userId, type);
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<NotificationDto> getForUser(String userId, int limit) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit))
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public long countUnread(String userId) {
        return repo.countByUserIdAndReadFalse(userId);
    }

    @Transactional
    public void markRead(String userId, String id) {
        repo.findByIdAndUserId(id, userId).ifPresent(n -> {
            n.setRead(true);
            repo.save(n);
        });
    }

    @Transactional
    public void markAllRead(String userId) {
        repo.markAllReadForUser(userId);
    }

    // ── Mapper ────────────────────────────────────────────────────────────────

    private NotificationDto toDto(Notification n) {
        return NotificationDto.builder()
                .id(n.getId())
                .type(n.getType())
                .title(n.getTitle())
                .message(n.getMessage())
                .relatedEntityId(n.getRelatedEntityId())
                .relatedEntityType(n.getRelatedEntityType())
                .link(n.getLink())
                .read(n.isRead())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
