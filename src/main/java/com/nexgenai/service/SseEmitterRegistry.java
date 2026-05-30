package com.nexgenai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe registry of live SSE connections, keyed by userId.
 * One user may have multiple open tabs — all receive the event.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SseEmitterRegistry {

    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(String userId) {
        SseEmitter emitter = new SseEmitter(0L);   // 0 = never time out on the server side
        emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> {
            List<SseEmitter> list = emitters.get(userId);
            if (list != null) list.remove(emitter);
            log.debug("SSE closed for user {}", userId);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        log.debug("SSE connected: userId={}, open={}", userId,
                emitters.getOrDefault(userId, List.of()).size());
        return emitter;
    }

    public void sendToUser(String userId, Object payload) {
        List<SseEmitter> list = emitters.getOrDefault(userId, Collections.emptyList());
        if (list.isEmpty()) return;

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize SSE payload: {}", e.getMessage());
            return;
        }

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("notification").data(json));
            } catch (Exception e) {
                log.debug("Dead SSE emitter removed for user {}", userId);
                dead.add(emitter);
            }
        }
        list.removeAll(dead);
    }
}
