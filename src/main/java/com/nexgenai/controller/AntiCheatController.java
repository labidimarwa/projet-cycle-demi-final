package com.nexgenai.controller;

import com.nexgenai.dto.anticheat.RecordAntiCheatEventRequest;
import com.nexgenai.model.AntiCheatEvent;
import com.nexgenai.repository.AntiCheatEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/anti-cheat")
@RequiredArgsConstructor
public class AntiCheatController {

    private final AntiCheatEventRepository antiCheatEventRepository;

    /**
     * Returns all anti-cheat events for a given session (RH or Tech).
     * GET /api/v1/anti-cheat/sessions/{sessionId}/events
     */
    @GetMapping("/sessions/{sessionId}/events")
    public ResponseEntity<List<AntiCheatEvent>> getEventsBySession(
            @PathVariable String sessionId) {
        List<AntiCheatEvent> events = antiCheatEventRepository
                .findBySessionIdOrderByOccurredAtAsc(sessionId);
        return ResponseEntity.ok(events);
    }

    /**
     * Records a new anti-cheat event from the frontend.
     * POST /api/v1/anti-cheat/events
     */
    @PostMapping("/events")
    public ResponseEntity<AntiCheatEvent> recordEvent(
            @RequestBody RecordAntiCheatEventRequest request) {
        AntiCheatEvent event = AntiCheatEvent.builder()
                .testId(request.getTestId())
                .sessionId(request.getSessionId())
                .type(request.getType())
                .detail(request.getDetail())
                .questionIndex(request.getQuestionIndex())
                .occurredAt(request.getOccurredAt() != null
                        ? request.getOccurredAt()
                        : LocalDateTime.now())
                .build();
        return ResponseEntity.ok(antiCheatEventRepository.save(event));
    }
}