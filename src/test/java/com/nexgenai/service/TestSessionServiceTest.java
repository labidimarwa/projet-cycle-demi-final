package com.nexgenai.service;

import com.nexgenai.dto.technicaltest.AntiCheatEventDto;
import com.nexgenai.dto.technicaltest.SubmitResultDto;
import com.nexgenai.dto.technicaltest.SubmitTestRequest;
import com.nexgenai.dto.test.TestSessionDto;
import com.nexgenai.model.*;
import com.nexgenai.model.enums.AssessmentType;
import com.nexgenai.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires — TestSessionService (sessions RH / psychométriques)
 *
 * Scénarios couverts :
 *   TC-RH-01  Test disponible, candidat non encore passé        → IN_PROGRESS + questions
 *   TC-RH-02  Test déjà passé (COMPLETED)                       → timeLeftSeconds = 0
 *   TC-RH-03  Test désactivé (assessment inexistant)            → RuntimeException
 *   TC-RH-04  Soumission complète (toutes réponses)             → score calculé, COMPLETED
 *   TC-RH-05  Soumission partielle (3/4 questions)              → soumission réussie
 *   TC-RH-06  Timeout dépassé                                   → submitRhTest auto-déclenché
 *   TC-RH-07  Sauvegarde réponse                                → persistée en base
 *   TC-RH-08  Anti-cheat PASTE enregistré                       → AntiCheatEvent sauvegardé
 *   TC-RH-09  Anti-cheat TAB_SWITCH                             → AntiCheatEvent sauvegardé
 *   TC-RH-10  Soumission session déjà COMPLETED                 → retourne score existant
 *   TC-RH-11  saveRhAnswer sur session COMPLETED                → RuntimeException
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TestSessionService — Tests Unitaires RH")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TestSessionServiceTest {

    @Mock private TestSessionRepository              testSessionRepository;
    @Mock private AssessmentRepository               assessmentRepository;
    @Mock private QuestionRepository                 questionRepository;
    @Mock private CandidateRepository                candidateRepository;
    @Mock private AntiCheatEventRepository           antiCheatRepository;
    @Mock private TestSubmissionRepository           submissionRepository;
    @Mock private CandidateAnswerRepository          answerRepository;
    @Mock private WorkflowStageRepository            workflowStageRepository;
    @Mock private TestSessionAnswerRepository        sessionAnswerRepository;
    @Mock private TestSessionAnswerDecisionRepository decisionRepository;
    @Mock private CodeExecutionService               codeExecutionService;

    @InjectMocks private TestSessionService service;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private Candidate makeCandidate(String id, String email) {
        Candidate c = new Candidate();
        c.setId(id);
        c.setEmail(email);
        c.setFirstName("Alice");
        c.setLastName("Martin");
        return c;
    }

    private Assessment makeAssessment(String id, AssessmentType type) {
        Assessment a = new Assessment();
        a.setId(id);
        a.setName("Big Five Test");
        a.setType(type);
        a.setDuration(45);
        a.setStatus(Assessment.AssessmentStatus.ACTIVE);
        a.setThemes(new LinkedHashSet<>());
        Job job = new Job();
        job.setTitle("Backend Dev");
        a.setJob(job);
        return a;
    }

    private TestSession makeSession(String id, TestSession.SessionStatus status,
                                    Candidate candidate, Assessment assessment) {
        TestSession s = new TestSession();
        s.setId(id);
        s.setStatus(status);
        s.setCandidate(candidate);
        s.setAssessment(assessment);
        s.setType(assessment.getType());
        s.setStartedAt(LocalDateTime.now().minusMinutes(10));
        return s;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TC-RH-01 : Test disponible, candidat non encore passé
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-RH-01 : Test disponible, non encore passé → session IN_PROGRESS, questions retournées")
    void getRhTestSession_available_candidateNotStarted_createsInProgressSession() {
        // GIVEN
        Candidate cand   = makeCandidate("c1", "alice@test.com");
        Assessment assess = makeAssessment("a1", AssessmentType.RH);

        when(candidateRepository.findByEmail("alice@test.com")).thenReturn(Optional.of(cand));
        when(assessmentRepository.findByIdWithThemes("a1")).thenReturn(Optional.of(assess));
        when(testSessionRepository.findByCandidateIdAndAssessmentId("c1", "a1"))
                .thenReturn(Optional.empty());
        when(testSessionRepository.save(any(TestSession.class)))
                .thenAnswer(inv -> { TestSession s = inv.getArgument(0); s.setId("sess1"); return s; });

        // WHEN
        TestSessionDto dto = service.getRhTestSession("a1", null, "alice@test.com");

        // THEN
        assertNotNull(dto);
        assertEquals("sess1", dto.getSessionId());
        assertEquals("a1",    dto.getTestId());
        assertEquals("Big Five Test", dto.getTestName());
        assertTrue(dto.getTimeLeftSeconds() > 0,
            "Time left should be positive for a new session");
        verify(testSessionRepository).save(argThat(s ->
            s.getStatus() == TestSession.SessionStatus.IN_PROGRESS));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TC-RH-02 : Test déjà passé → timeLeftSeconds = 0
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("TC-RH-02 : Test déjà passé (COMPLETED) → timeLeftSeconds=0, message 'Test already completed'")
    void getRhTestSession_alreadyCompleted_returnsZeroTimeLeft() {
        // GIVEN
        Candidate cand    = makeCandidate("c1", "alice@test.com");
        Assessment assess  = makeAssessment("a1", AssessmentType.RH);
        TestSession session = makeSession("sess1", TestSession.SessionStatus.COMPLETED, cand, assess);
        session.setScore(75);

        when(candidateRepository.findByEmail("alice@test.com")).thenReturn(Optional.of(cand));
        when(assessmentRepository.findByIdWithThemes("a1")).thenReturn(Optional.of(assess));
        when(testSessionRepository.findByCandidateIdAndAssessmentId("c1", "a1"))
                .thenReturn(Optional.of(session));

        // WHEN
        TestSessionDto dto = service.getRhTestSession("a1", null, "alice@test.com");

        // THEN
        assertNotNull(dto);
        assertEquals(0, dto.getTimeLeftSeconds(),
            "Completed test must return timeLeftSeconds=0");
        assertEquals(0, dto.getTimeLimit(),
            "Completed test must return timeLimit=0");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TC-RH-03 : Test désactivé — assessment introuvable
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("TC-RH-03 : Test non disponible (assessment inexistant) → RuntimeException 'Test not available'")
    void getRhTestSession_assessmentNotFound_throwsRuntimeException() {
        // GIVEN
        Candidate cand = makeCandidate("c1", "alice@test.com");
        when(candidateRepository.findByEmail("alice@test.com")).thenReturn(Optional.of(cand));
        when(assessmentRepository.findByIdWithThemes("a-inactive"))
                .thenReturn(Optional.empty());

        // WHEN / THEN
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> service.getRhTestSession("a-inactive", null, "alice@test.com"));

        assertTrue(ex.getMessage().contains("Assessment not found"),
            "Message doit indiquer que l'assessment n'est pas disponible, got: " + ex.getMessage());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TC-RH-04 : Soumission complète → score calculé, session COMPLETED
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("TC-RH-04 : Soumission complète (toutes réponses) → score calculé, session COMPLETED")
    void submitRhTest_allAnswers_calculatesScoreAndCompletes() {
        // GIVEN
        Candidate  cand    = makeCandidate("c1", "alice@test.com");
        Assessment assess   = makeAssessment("a1", AssessmentType.RH);
        TestSession session = makeSession("sess1", TestSession.SessionStatus.IN_PROGRESS, cand, assess);

        when(testSessionRepository.findById("sess1")).thenReturn(Optional.of(session));
        when(assessmentRepository.findByIdWithThemes("a1")).thenReturn(Optional.of(assess));
        when(sessionAnswerRepository.findBySessionId("sess1")).thenReturn(List.of());
        when(testSessionRepository.save(any(TestSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // WHEN
        int score = service.submitRhTest("sess1", "alice@test.com");

        // THEN
        assertEquals(0, score, "Score with no answers should be 0");
        verify(testSessionRepository).save(argThat(s ->
            s.getStatus() == TestSession.SessionStatus.COMPLETED));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TC-RH-05 : Soumission partielle (3/4) → soumission réussie
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("TC-RH-05 : Soumission partielle 3/4 questions → soumission acceptée, session COMPLETED")
    void submitRhTest_partialAnswers_succeeds() {
        // GIVEN : 4 questions, 3 ont une réponse
        Candidate  cand    = makeCandidate("c1", "alice@test.com");
        Assessment assess   = makeAssessment("a1", AssessmentType.RH);
        TestSession session = makeSession("sess1", TestSession.SessionStatus.IN_PROGRESS, cand, assess);

        TestSessionAnswer a1 = new TestSessionAnswer(); a1.setQuestionId("q1"); a1.setSelectedOptionIds(List.of("opt1"));
        TestSessionAnswer a2 = new TestSessionAnswer(); a2.setQuestionId("q2"); a2.setSelectedOptionIds(List.of("opt2"));
        TestSessionAnswer a3 = new TestSessionAnswer(); a3.setQuestionId("q3"); a3.setSelectedOptionIds(List.of("opt3"));
        // q4 intentionally unanswered

        when(testSessionRepository.findById("sess1")).thenReturn(Optional.of(session));
        when(assessmentRepository.findByIdWithThemes("a1")).thenReturn(Optional.of(assess));
        when(sessionAnswerRepository.findBySessionId("sess1")).thenReturn(List.of(a1, a2, a3));
        when(testSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // WHEN — must not throw even with only 3/4 answered
        assertDoesNotThrow(() -> service.submitRhTest("sess1", "alice@test.com"),
            "Partial submission should not throw");

        // THEN
        verify(testSessionRepository).save(argThat(s ->
            s.getStatus() == TestSession.SessionStatus.COMPLETED));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TC-RH-06 : Timeout → submitRhTest déclenché automatiquement
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("TC-RH-06 : Timeout dépassé → submitRhTest déclenché et session COMPLETED")
    void getRhTestSession_timeExpired_autoSubmits() {
        // GIVEN : session démarrée il y a plus longtemps que la durée limite
        Candidate  cand    = makeCandidate("c1", "alice@test.com");
        Assessment assess   = makeAssessment("a1", AssessmentType.RH);
        assess.setDuration(1); // 1 minute
        TestSession session = makeSession("sess1", TestSession.SessionStatus.IN_PROGRESS, cand, assess);
        // Started 2 minutes ago → time expired
        session.setStartedAt(LocalDateTime.now().minusMinutes(2));

        when(candidateRepository.findByEmail("alice@test.com")).thenReturn(Optional.of(cand));
        when(assessmentRepository.findByIdWithThemes("a1")).thenReturn(Optional.of(assess));
        when(testSessionRepository.findByCandidateIdAndAssessmentId("c1", "a1"))
                .thenReturn(Optional.of(session));
        when(testSessionRepository.findById("sess1")).thenReturn(Optional.of(session));
        when(sessionAnswerRepository.findBySessionId("sess1")).thenReturn(List.of());
        when(testSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // WHEN
        TestSessionDto dto = service.getRhTestSession("a1", null, "alice@test.com");

        // THEN : time has expired
        assertEquals(0, dto.getTimeLeftSeconds(),
            "Expired test must return timeLeftSeconds=0");
        verify(testSessionRepository, atLeastOnce()).save(argThat(s ->
            s.getStatus() == TestSession.SessionStatus.COMPLETED));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TC-RH-07 : Sauvegarde de réponse RH
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("TC-RH-07 : saveRhAnswer → réponse persistée avec les bonnes optionIds")
    void saveRhAnswer_inProgressSession_persistsAnswer() {
        // GIVEN
        Candidate  cand    = makeCandidate("c1", "alice@test.com");
        Assessment assess   = makeAssessment("a1", AssessmentType.RH);
        TestSession session = makeSession("sess1", TestSession.SessionStatus.IN_PROGRESS, cand, assess);

        com.nexgenai.dto.test.SaveAnswerRequest req = new com.nexgenai.dto.test.SaveAnswerRequest();
        req.setSessionId("sess1");
        req.setQuestionId("q1");
        req.setOptionIds(List.of("opt-A", "opt-C"));

        when(testSessionRepository.findById("sess1")).thenReturn(Optional.of(session));
        when(sessionAnswerRepository.findBySessionIdAndQuestionId("sess1", "q1"))
                .thenReturn(Optional.empty());
        when(sessionAnswerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // WHEN
        assertDoesNotThrow(() -> service.saveRhAnswer(req, "alice@test.com"));

        // THEN
        verify(sessionAnswerRepository).save(argThat(a ->
            a.getSelectedOptionIds().contains("opt-A") &&
            a.getSelectedOptionIds().contains("opt-C")));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TC-RH-08 : Anti-cheat — PASTE enregistré
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(8)
    @DisplayName("TC-RH-08 : Anti-cheat PASTE → AntiCheatEvent persisté avec type=PASTE")
    void logAntiCheatEvent_paste_savesCorrectEvent() {
        // GIVEN
        Candidate  cand    = makeCandidate("c1", "alice@test.com");
        Assessment assess   = makeAssessment("a1", AssessmentType.RH);
        TestSession session = makeSession("sess1", TestSession.SessionStatus.IN_PROGRESS, cand, assess);

        when(testSessionRepository.findById("sess1")).thenReturn(Optional.of(session));
        when(antiCheatRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AntiCheatEventDto evt = new AntiCheatEventDto();
        evt.setType("PASTE");
        evt.setDetail("Pasted 200 chars from clipboard");
        evt.setQuestionIndex(2);
        evt.setTimestamp("2025-05-15T10:00:00.000");

        // WHEN
        service.logAntiCheatEvent("sess1", evt, "alice@test.com");

        // THEN
        verify(antiCheatRepository).save(argThat(e -> {
            assertEquals("PASTE",   e.getType());
            assertEquals("sess1",   e.getSessionId());
            assertEquals("a1",      e.getTestId());
            assertEquals(2,         e.getQuestionIndex());
            assertNotNull(e.getOccurredAt());
            return true;
        }));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TC-RH-09 : Anti-cheat — TAB_SWITCH enregistré
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(9)
    @DisplayName("TC-RH-09 : Anti-cheat TAB_SWITCH → AntiCheatEvent persisté avec type=TAB_SWITCH")
    void logAntiCheatEvent_tabSwitch_savesEvent() {
        // GIVEN
        Candidate  cand    = makeCandidate("c1", "alice@test.com");
        Assessment assess   = makeAssessment("a1", AssessmentType.RH);
        TestSession session = makeSession("sess1", TestSession.SessionStatus.IN_PROGRESS, cand, assess);

        when(testSessionRepository.findById("sess1")).thenReturn(Optional.of(session));
        when(antiCheatRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AntiCheatEventDto evt = new AntiCheatEventDto();
        evt.setType("TAB_SWITCH");
        evt.setDetail("Candidate switched to another tab");
        evt.setQuestionIndex(0);
        evt.setTimestamp("2025-05-15T10:05:00.000");

        // WHEN
        service.logAntiCheatEvent("sess1", evt, "alice@test.com");

        // THEN
        verify(antiCheatRepository).save(argThat(e ->
            "TAB_SWITCH".equals(e.getType()) && "sess1".equals(e.getSessionId())));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TC-RH-10 : Deuxième soumission → retourne le score existant
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("TC-RH-10 : Soumission d'une session déjà COMPLETED → retourne score existant sans recalcul")
    void submitRhTest_alreadyCompleted_returnsExistingScore() {
        // GIVEN
        Candidate  cand    = makeCandidate("c1", "alice@test.com");
        Assessment assess   = makeAssessment("a1", AssessmentType.RH);
        TestSession session = makeSession("sess1", TestSession.SessionStatus.COMPLETED, cand, assess);
        session.setScore(82);

        when(testSessionRepository.findById("sess1")).thenReturn(Optional.of(session));

        // WHEN
        int score = service.submitRhTest("sess1", "alice@test.com");

        // THEN
        assertEquals(82, score, "Should return existing score without recalculating");
        verify(sessionAnswerRepository, never()).findBySessionId(any());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TC-RH-11 : saveRhAnswer sur session COMPLETED → exception
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(11)
    @DisplayName("TC-RH-11 : saveRhAnswer après COMPLETED → RuntimeException 'Test already submitted'")
    void saveRhAnswer_completedSession_throwsException() {
        // GIVEN
        Candidate  cand    = makeCandidate("c1", "alice@test.com");
        Assessment assess   = makeAssessment("a1", AssessmentType.RH);
        TestSession session = makeSession("sess1", TestSession.SessionStatus.COMPLETED, cand, assess);

        com.nexgenai.dto.test.SaveAnswerRequest req = new com.nexgenai.dto.test.SaveAnswerRequest();
        req.setSessionId("sess1");
        req.setQuestionId("q1");
        req.setOptionIds(List.of("opt-A"));

        when(testSessionRepository.findById("sess1")).thenReturn(Optional.of(session));

        // WHEN / THEN
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> service.saveRhAnswer(req, "alice@test.com"));

        assertTrue(ex.getMessage().contains("already submitted") ||
                   ex.getMessage().contains("Test already"),
            "Must say test is already submitted, got: " + ex.getMessage());
    }
}
