package com.nexgenai.service;

import com.nexgenai.dto.technicaltest.AntiCheatEventDto;
import com.nexgenai.dto.technicaltest.AntiCheatReportDto;
import com.nexgenai.model.AntiCheatEvent;
import com.nexgenai.repository.AntiCheatEventRepository;
import com.nexgenai.repository.TestSessionRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires d'AntiCheatService.
 *
 * Couvre :
 *   - Persistance des événements anti-triche (recordEvent)
 *   - Construction du rapport de risque (buildReport)
 *   - Calcul du score de risque et classification LOW/MEDIUM/HIGH
 *
 * Score de risque :
 *   TAB_SWITCH × 3 + PASTE × 2 + DEVTOOLS × 4 + WINDOW_BLUR × 1
 *   < 4   → LOW
 *   4–9   → MEDIUM
 *   ≥ 10  → HIGH
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AntiCheatService — Tests Unitaires")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AntiCheatServiceTest {

    @Mock  private AntiCheatEventRepository antiCheatEventRepository;
    @Mock  private TestSessionRepository    testSessionRepository;
    @InjectMocks private AntiCheatService   antiCheatService;

    // ══════════════════════════════════════════════════════════════════════════
    // recordEvent
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-AC-01 : recordEvent → entité sauvegardée avec testId, sessionId, type corrects")
    void recordEvent_withFullDto_savesEntityWithCorrectFields() {
        // GIVEN
        AntiCheatEventDto dto = new AntiCheatEventDto();
        dto.setType("TAB_SWITCH");
        dto.setDetail("Switched to Chrome tab");
        dto.setQuestionIndex(2);
        dto.setSessionId("session-001");
        dto.setTimestamp("2024-05-10T14:30:00.000");

        when(antiCheatEventRepository.save(any(AntiCheatEvent.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        // WHEN
        antiCheatService.recordEvent("test-001", dto);

        // THEN
        verify(antiCheatEventRepository).save(argThat(event -> {
            assertEquals("test-001",    event.getTestId());
            assertEquals("session-001", event.getSessionId());
            assertEquals("TAB_SWITCH",  event.getType());
            assertEquals("Switched to Chrome tab", event.getDetail());
            assertEquals(2,             event.getQuestionIndex());
            assertNotNull(event.getOccurredAt());
            return true;
        }));
    }

    @Test
    @Order(2)
    @DisplayName("TC-AC-02 : recordEvent avec timestamp 'Z' → timestamp parsé en LocalDateTime")
    void recordEvent_withZTimestamp_parsesTimestampCorrectly() {
        // GIVEN : le frontend envoie un ISO timestamp avec Z à la fin
        AntiCheatEventDto dto = new AntiCheatEventDto();
        dto.setType("PASTE");
        dto.setDetail("Pasted text");
        dto.setQuestionIndex(0);
        dto.setSessionId("sess-002");
        dto.setTimestamp("2024-06-15T09:45:30.123Z");

        when(antiCheatEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // WHEN
        antiCheatService.recordEvent("test-002", dto);

        // THEN : pas d'exception + occurredAt est la date parsée
        verify(antiCheatEventRepository).save(argThat(event ->
            event.getOccurredAt() != null
            && event.getOccurredAt().getYear()   == 2024
            && event.getOccurredAt().getMonthValue() == 6
            && event.getOccurredAt().getDayOfMonth() == 15
        ));
    }

    @Test
    @Order(3)
    @DisplayName("TC-AC-03 : recordEvent sans timestamp → occurredAt ≈ maintenant")
    void recordEvent_withoutTimestamp_usesCurrentDateTime() {
        // GIVEN
        AntiCheatEventDto dto = new AntiCheatEventDto();
        dto.setType("DEVTOOLS");
        dto.setDetail("DevTools detected");
        dto.setQuestionIndex(1);
        dto.setSessionId("sess-003");
        dto.setTimestamp(null); // pas de timestamp

        when(antiCheatEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        // WHEN
        antiCheatService.recordEvent("test-003", dto);

        LocalDateTime after = LocalDateTime.now().plusSeconds(1);

        // THEN
        verify(antiCheatEventRepository).save(argThat(event -> {
            assertNotNull(event.getOccurredAt());
            assertTrue(event.getOccurredAt().isAfter(before)
                    && event.getOccurredAt().isBefore(after),
                "occurredAt doit être ≈ maintenant");
            return true;
        }));
    }

    @Test
    @Order(4)
    @DisplayName("TC-AC-04 : recordEvent → save() appelé exactement une fois")
    void recordEvent_callsSaveExactlyOnce() {
        AntiCheatEventDto dto = new AntiCheatEventDto();
        dto.setType("WINDOW_BLUR");
        dto.setSessionId("s");
        when(antiCheatEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        antiCheatService.recordEvent("t", dto);

        verify(antiCheatEventRepository, times(1)).save(any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // buildReport — calcul du score de risque
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("TC-AC-05 : buildReport sans événements → ALL counts=0, riskLevel=LOW")
    void buildReport_noEvents_returnsLowRiskWithZeroCounts() {
        // GIVEN
        when(antiCheatEventRepository
            .findByTestIdAndSessionIdOrderByOccurredAtAsc("t1", "s1"))
            .thenReturn(List.of());

        // WHEN
        AntiCheatReportDto report = antiCheatService.buildReport("t1", "s1");

        // THEN
        assertNotNull(report);
        assertEquals("LOW",    report.getRiskLevel());
        assertEquals(0, report.getTabSwitchCount());
        assertEquals(0, report.getPasteCount());
        assertEquals(0, report.getBlurCount());
        assertEquals(0, report.getDevToolsAttempts());
        assertEquals(0, report.getTotalEvents());
    }

    @Test
    @Order(6)
    @DisplayName("TC-AC-06 : 1 WINDOW_BLUR → score=1 → riskLevel=LOW")
    void buildReport_oneWindowBlur_lowRisk_score1() {
        // GIVEN : score = blur×1 = 1×1 = 1 → LOW
        List<AntiCheatEvent> events = List.of(
            makeEvent("WINDOW_BLUR", "sess", "test")
        );
        when(antiCheatEventRepository
            .findByTestIdAndSessionIdOrderByOccurredAtAsc("test", "sess"))
            .thenReturn(events);

        // WHEN
        AntiCheatReportDto report = antiCheatService.buildReport("test", "sess");

        // THEN
        assertEquals("LOW", report.getRiskLevel());
        assertEquals(1, report.getBlurCount());
        assertEquals(0, report.getTabSwitchCount());
        assertEquals(1, report.getTotalEvents());
    }

    @Test
    @Order(7)
    @DisplayName("TC-AC-07 : 1 TAB_SWITCH + 1 WINDOW_BLUR → score=4 → riskLevel=MEDIUM")
    void buildReport_oneTabSwitchOneBLur_score4_mediumRisk() {
        // GIVEN : score = 3×1 + 1×1 = 4 → MEDIUM (seuil=4)
        List<AntiCheatEvent> events = List.of(
            makeEvent("TAB_SWITCH",   "sess", "test"),
            makeEvent("WINDOW_BLUR",  "sess", "test")
        );
        when(antiCheatEventRepository
            .findByTestIdAndSessionIdOrderByOccurredAtAsc("test", "sess"))
            .thenReturn(events);

        // WHEN
        AntiCheatReportDto report = antiCheatService.buildReport("test", "sess");

        // THEN
        assertEquals("MEDIUM", report.getRiskLevel());
        assertEquals(1, report.getTabSwitchCount());
        assertEquals(1, report.getBlurCount());
    }

    @Test
    @Order(8)
    @DisplayName("TC-AC-08 : 3 TAB_SWITCH + 1 WINDOW_BLUR → score=10 → riskLevel=HIGH")
    void buildReport_3TabSwitches1Blur_score10_highRisk() {
        // GIVEN : score = 3×3 + 1×1 = 10 → HIGH (seuil=10)
        List<AntiCheatEvent> events = List.of(
            makeEvent("TAB_SWITCH",  "sess", "test"),
            makeEvent("TAB_SWITCH",  "sess", "test"),
            makeEvent("TAB_SWITCH",  "sess", "test"),
            makeEvent("WINDOW_BLUR", "sess", "test")
        );
        when(antiCheatEventRepository
            .findByTestIdAndSessionIdOrderByOccurredAtAsc("test", "sess"))
            .thenReturn(events);

        // WHEN
        AntiCheatReportDto report = antiCheatService.buildReport("test", "sess");

        // THEN
        assertEquals("HIGH", report.getRiskLevel());
        assertEquals(3, report.getTabSwitchCount());
    }

    @Test
    @Order(9)
    @DisplayName("TC-AC-09 : 2 DEVTOOLS → score=8 → riskLevel=MEDIUM")
    void buildReport_2DevTools_score8_mediumRisk() {
        // GIVEN : score = 2×4 = 8 → MEDIUM (8 < 10)
        List<AntiCheatEvent> events = List.of(
            makeEvent("DEVTOOLS", "sess", "test"),
            makeEvent("DEVTOOLS", "sess", "test")
        );
        when(antiCheatEventRepository
            .findByTestIdAndSessionIdOrderByOccurredAtAsc("test", "sess"))
            .thenReturn(events);

        // WHEN
        AntiCheatReportDto report = antiCheatService.buildReport("test", "sess");

        // THEN
        assertEquals("MEDIUM", report.getRiskLevel());
        assertEquals(2, report.getDevToolsAttempts());
    }

    @Test
    @Order(10)
    @DisplayName("TC-AC-10 : 3 DEVTOOLS → score=12 → riskLevel=HIGH")
    void buildReport_3DevTools_score12_highRisk() {
        // GIVEN : score = 3×4 = 12 → HIGH
        List<AntiCheatEvent> events = List.of(
            makeEvent("DEVTOOLS", "sess", "test"),
            makeEvent("DEVTOOLS", "sess", "test"),
            makeEvent("DEVTOOLS", "sess", "test")
        );
        when(antiCheatEventRepository
            .findByTestIdAndSessionIdOrderByOccurredAtAsc("test", "sess"))
            .thenReturn(events);

        AntiCheatReportDto report = antiCheatService.buildReport("test", "sess");

        assertEquals("HIGH", report.getRiskLevel());
    }

    @Test
    @Order(11)
    @DisplayName("TC-AC-11 : 2 PASTE → score=4 → riskLevel=MEDIUM")
    void buildReport_2Pastes_score4_mediumRisk() {
        // GIVEN : score = 2×2 = 4 → MEDIUM
        List<AntiCheatEvent> events = List.of(
            makeEvent("PASTE", "sess", "test"),
            makeEvent("PASTE", "sess", "test")
        );
        when(antiCheatEventRepository
            .findByTestIdAndSessionIdOrderByOccurredAtAsc("test", "sess"))
            .thenReturn(events);

        AntiCheatReportDto report = antiCheatService.buildReport("test", "sess");

        assertEquals("MEDIUM", report.getRiskLevel());
        assertEquals(2, report.getPasteCount());
    }

    @Test
    @Order(12)
    @DisplayName("TC-AC-12 : mix d'événements → tous les compteurs corrects")
    void buildReport_mixedEvents_allCountsAreCorrect() {
        // GIVEN : 2 TAB, 1 PASTE, 3 BLUR, 1 DEVTOOLS → score = 6+2+3+4 = 15 → HIGH
        List<AntiCheatEvent> events = List.of(
            makeEvent("TAB_SWITCH",  "s", "t"),
            makeEvent("TAB_SWITCH",  "s", "t"),
            makeEvent("PASTE",       "s", "t"),
            makeEvent("WINDOW_BLUR", "s", "t"),
            makeEvent("WINDOW_BLUR", "s", "t"),
            makeEvent("WINDOW_BLUR", "s", "t"),
            makeEvent("DEVTOOLS",    "s", "t")
        );
        when(antiCheatEventRepository
            .findByTestIdAndSessionIdOrderByOccurredAtAsc("t", "s"))
            .thenReturn(events);

        AntiCheatReportDto report = antiCheatService.buildReport("t", "s");

        assertEquals("HIGH", report.getRiskLevel());
        assertEquals(2, report.getTabSwitchCount());
        assertEquals(1, report.getPasteCount());
        assertEquals(3, report.getBlurCount());
        assertEquals(1, report.getDevToolsAttempts());
        assertEquals(7, report.getTotalEvents());
        assertNotNull(report.getEvents());
        assertEquals(7, report.getEvents().size());
    }

    @Test
    @Order(13)
    @DisplayName("TC-AC-13 : buildReport → sessionId présent dans le rapport")
    void buildReport_sessionIdPresentInReport() {
        when(antiCheatEventRepository
            .findByTestIdAndSessionIdOrderByOccurredAtAsc("t", "my-session"))
            .thenReturn(List.of());

        AntiCheatReportDto report = antiCheatService.buildReport("t", "my-session");

        assertEquals("my-session", report.getSessionId());
    }

    @Test
    @Order(14)
    @DisplayName("TC-AC-14 : score 3 (1 PASTE + 1 BLUR) → juste en dessous MEDIUM → LOW")
    void buildReport_score3_belowMediumThreshold_lowRisk() {
        // score = 2×1 + 1×1 = 3 → LOW (seuil MEDIUM = 4)
        List<AntiCheatEvent> events = List.of(
            makeEvent("PASTE",       "s", "t"),
            makeEvent("WINDOW_BLUR", "s", "t")
        );
        when(antiCheatEventRepository
            .findByTestIdAndSessionIdOrderByOccurredAtAsc("t", "s"))
            .thenReturn(events);

        AntiCheatReportDto report = antiCheatService.buildReport("t", "s");
        assertEquals("LOW", report.getRiskLevel());
    }

    @Test
    @Order(15)
    @DisplayName("TC-AC-15 : score 9 (3 PASTE) → juste en dessous HIGH → MEDIUM")
    void buildReport_score9_belowHighThreshold_mediumRisk() {
        // score = 3×2 = 6... wait that's 6. Let me use 1 DEVTOOLS (4) + 1 PASTE (2) + 1 BLUR (1) = 7
        // Actually 3 pastes = 3×2 = 6 → MEDIUM
        // For score 9: 3 PASTE (6) + 3 BLUR (3) = 9 → MEDIUM
        List<AntiCheatEvent> events = List.of(
            makeEvent("PASTE",       "s", "t"),
            makeEvent("PASTE",       "s", "t"),
            makeEvent("PASTE",       "s", "t"),
            makeEvent("WINDOW_BLUR", "s", "t"),
            makeEvent("WINDOW_BLUR", "s", "t"),
            makeEvent("WINDOW_BLUR", "s", "t")
        );
        when(antiCheatEventRepository
            .findByTestIdAndSessionIdOrderByOccurredAtAsc("t", "s"))
            .thenReturn(events);

        AntiCheatReportDto report = antiCheatService.buildReport("t", "s");
        assertEquals("MEDIUM", report.getRiskLevel(),
            "Score 9 doit être MEDIUM (seuil HIGH = 10)");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AntiCheatEvent makeEvent(String type, String sessionId, String testId) {
        return AntiCheatEvent.builder()
            .id(java.util.UUID.randomUUID().toString())
            .testId(testId)
            .sessionId(sessionId)
            .type(type)
            .detail("detail for " + type)
            .questionIndex(0)
            .occurredAt(LocalDateTime.now())
            .build();
    }
}
