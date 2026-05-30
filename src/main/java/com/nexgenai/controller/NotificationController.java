package com.nexgenai.controller;

import com.nexgenai.dto.notification.NotificationDto;
import com.nexgenai.model.User;
import com.nexgenai.service.NotificationService;
import com.nexgenai.service.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notifService;
    private final SseEmitterRegistry  registry;

    /**
     * SSE stream — Angular connects here after login and keeps the connection open.
     * The browser uses fetch() + ReadableStream to support the Authorization header.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication auth) {
        String userId = ((User) auth.getPrincipal()).getId();
        return registry.register(userId);
    }

    /** Returns the last N notifications (default 30) for the authenticated user. */
    @GetMapping
    public ResponseEntity<List<NotificationDto>> list(
            Authentication auth,
            @RequestParam(defaultValue = "30") int limit) {
        String userId = ((User) auth.getPrincipal()).getId();
        return ResponseEntity.ok(notifService.getForUser(userId, limit));
    }

    /** Returns the unread count badge value. */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(Authentication auth) {
        String userId = ((User) auth.getPrincipal()).getId();
        return ResponseEntity.ok(Map.of("count", notifService.countUnread(userId)));
    }

    /** Mark a single notification as read. */
    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable String id, Authentication auth) {
        String userId = ((User) auth.getPrincipal()).getId();
        notifService.markRead(userId, id);
        return ResponseEntity.noContent().build();
    }

    /** Mark all notifications as read for the authenticated user. */
    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllRead(Authentication auth) {
        String userId = ((User) auth.getPrincipal()).getId();
        notifService.markAllRead(userId);
        return ResponseEntity.noContent().build();
    }
}
