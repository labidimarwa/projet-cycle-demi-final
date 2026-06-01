package com.nexgenai.service;

import com.nexgenai.dto.technicaltest.AntiCheatEventDto;
import com.nexgenai.dto.technicaltest.AntiCheatReportDto;
import com.nexgenai.model.AntiCheatEvent;
import com.nexgenai.model.TestSession;
import com.nexgenai.repository.AntiCheatEventRepository;
import com.nexgenai.repository.TestSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AntiCheatService {

    private final AntiCheatEventRepository antiCheatEventRepository;
    private final TestSessionRepository    testSessionRepository;

    /**
     * Persiste un événement de triche détecté par le frontend.
     */
    @Transactional
    public void recordEvent(String testId, AntiCheatEventDto dto) {
        log.warn("[ANTI-CHEAT] testId={} type={} detail={} q={}",
                testId, dto.getType(), dto.getDetail(), dto.getQuestionIndex());

        AntiCheatEvent entity = AntiCheatEvent.builder()
                .testId(testId)
                .sessionId(dto.getSessionId())
                .type(dto.getType())
                .detail(dto.getDetail())
                .questionIndex(dto.getQuestionIndex())
                .occurredAt(dto.getTimestamp() != null
                        ? LocalDateTime.parse(dto.getTimestamp().replace("Z", ""))
                        : LocalDateTime.now())
                .build();

        antiCheatEventRepository.save(entity);
    }

    /**
     * Construit le rapport anti-triche complet pour une session.
     */
    @Transactional(readOnly = true)
    public AntiCheatReportDto buildReport(String testId, String sessionId) {
        List<AntiCheatEvent> events = antiCheatEventRepository
                .findByTestIdAndSessionIdOrderByOccurredAtAsc(testId, sessionId);

        long tabSwitch   = events.stream().filter(e -> "TAB_SWITCH".equals(e.getType())).count();
        long paste       = events.stream().filter(e -> "PASTE".equals(e.getType())).count();
        long blur        = events.stream().filter(e -> "WINDOW_BLUR".equals(e.getType())).count();
        long devTools    = events.stream().filter(e -> "DEVTOOLS".equals(e.getType())).count();

        // Risk scoring
        long riskScore = tabSwitch * 3 + paste * 2 + devTools * 4 + blur;
        String riskLevel;
        if (riskScore >= 10) {
            riskLevel = "HIGH";
        } else if (riskScore >= 4) {
            riskLevel = "MEDIUM";
        } else {
            riskLevel = "LOW";
        }

        List<AntiCheatEventDto> eventDtos = events.stream()
                .map(e -> {
                    AntiCheatEventDto d = new AntiCheatEventDto();
                    d.setType(e.getType());
                    d.setDetail(e.getDetail());
                    d.setQuestionIndex(e.getQuestionIndex());
                    d.setTimestamp(e.getOccurredAt().toString());
                    return d;
                })
                .toList();

        return AntiCheatReportDto.builder()
                .sessionId(sessionId)
                .riskLevel(riskLevel)
                .tabSwitchCount((int) tabSwitch)
                .pasteCount((int) paste)
                .blurCount((int) blur)
                .devToolsAttempts((int) devTools)
                .totalEvents(events.size())
                .events(eventDtos)
                .build();
    }
}