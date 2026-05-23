package com.nexgenai.repository;

import com.nexgenai.model.Application;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests d'intégration d'ApplicationRepository avec H2.
 *
 * Vérifie :
 *   - existsByCandidateIdAndJobId
 *   - countByJobId
 *   - findByCandidateId
 *   - findByJobId
 *   - findByCandidateIdAndJobId
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("ApplicationRepository — Tests d'Intégration DataJPA")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApplicationRepositoryTest {

    @Autowired private ApplicationRepository applicationRepository;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Application saveApplication(String candidateId, String jobId) {
        Application app = Application.builder()
            .candidateId(candidateId)
            .jobId(jobId)
            .build();
        return applicationRepository.save(app);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // existsByCandidateIdAndJobId
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-AREPO-01 : existsByCandidateIdAndJobId(existant) → true")
    void existsByCandidateIdAndJobId_existingCombination_returnsTrue() {
        // GIVEN
        saveApplication("cand-001", "job-001");

        // WHEN + THEN
        assertTrue(applicationRepository.existsByCandidateIdAndJobId("cand-001", "job-001"));
    }

    @Test
    @Order(2)
    @DisplayName("TC-AREPO-02 : existsByCandidateIdAndJobId(non-existant) → false")
    void existsByCandidateIdAndJobId_nonExisting_returnsFalse() {
        assertFalse(applicationRepository.existsByCandidateIdAndJobId("ghost", "ghost-job"));
    }

    @Test
    @Order(3)
    @DisplayName("TC-AREPO-03 : existsByCandidateIdAndJobId(même candidat, autre job) → false")
    void existsByCandidateIdAndJobId_sameCandidateDifferentJob_returnsFalse() {
        // GIVEN
        saveApplication("cand-002", "job-A");

        // WHEN : même candidat, autre job
        assertFalse(applicationRepository.existsByCandidateIdAndJobId("cand-002", "job-B"));
    }

    @Test
    @Order(4)
    @DisplayName("TC-AREPO-04 : existsByCandidateIdAndJobId(autre candidat, même job) → false")
    void existsByCandidateIdAndJobId_differentCandidateSameJob_returnsFalse() {
        // GIVEN
        saveApplication("cand-003", "job-001");

        // WHEN : autre candidat, même job
        assertFalse(applicationRepository.existsByCandidateIdAndJobId("cand-999", "job-001"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // countByJobId
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("TC-AREPO-05 : countByJobId → retourne le bon nombre de candidatures")
    void countByJobId_returnsCorrectCount() {
        // GIVEN : 3 candidats postulent à job-count-001
        saveApplication("cand-A", "job-count-001");
        saveApplication("cand-B", "job-count-001");
        saveApplication("cand-C", "job-count-001");
        // 1 candidat postule à un autre job
        saveApplication("cand-A", "job-count-002");

        // WHEN
        long count = applicationRepository.countByJobId("job-count-001");

        // THEN
        assertEquals(3, count);
    }

    @Test
    @Order(6)
    @DisplayName("TC-AREPO-06 : countByJobId(job sans candidatures) → 0")
    void countByJobId_noApplications_returnsZero() {
        long count = applicationRepository.countByJobId("job-empty-xyz");
        assertEquals(0, count);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findByCandidateId
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("TC-AREPO-07 : findByCandidateId → retourne toutes les candidatures du candidat")
    void findByCandidateId_returnsAllApplicationsForCandidate() {
        // GIVEN : cand-finder postule à 3 jobs
        saveApplication("cand-finder", "job-F1");
        saveApplication("cand-finder", "job-F2");
        saveApplication("cand-finder", "job-F3");
        // Un autre candidat
        saveApplication("other-cand", "job-F1");

        // WHEN
        List<Application> result = applicationRepository.findByCandidateId("cand-finder");

        // THEN
        assertEquals(3, result.size());
        result.forEach(app ->
            assertEquals("cand-finder", app.getCandidateId())
        );
    }

    @Test
    @Order(8)
    @DisplayName("TC-AREPO-08 : findByCandidateId(candidat sans candidature) → liste vide")
    void findByCandidateId_noCandidature_returnsEmptyList() {
        List<Application> result = applicationRepository.findByCandidateId("cand-nobody");
        assertTrue(result.isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findByJobId
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(9)
    @DisplayName("TC-AREPO-09 : findByJobId → retourne toutes les candidatures pour un job")
    void findByJobId_returnsAllApplicationsForJob() {
        // GIVEN
        saveApplication("cand-X1", "job-by-id");
        saveApplication("cand-X2", "job-by-id");

        // WHEN
        List<Application> result = applicationRepository.findByJobId("job-by-id");

        // THEN
        assertFalse(result.isEmpty());
        result.forEach(app -> assertEquals("job-by-id", app.getJobId()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findByCandidateIdAndJobId
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("TC-AREPO-10 : findByCandidateIdAndJobId(existant) → retourne l'application")
    void findByCandidateIdAndJobId_existing_returnsApplication() {
        // GIVEN
        Application saved = saveApplication("cand-exact", "job-exact");

        // WHEN
        Optional<Application> result =
            applicationRepository.findByCandidateIdAndJobId("cand-exact", "job-exact");

        // THEN
        assertTrue(result.isPresent());
        assertEquals(saved.getId(), result.get().getId());
    }

    @Test
    @Order(11)
    @DisplayName("TC-AREPO-11 : findByCandidateIdAndJobId(non-existant) → Optional.empty()")
    void findByCandidateIdAndJobId_nonExisting_returnsEmpty() {
        Optional<Application> result =
            applicationRepository.findByCandidateIdAndJobId("ghost", "ghost-job");
        assertTrue(result.isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Contrainte d'unicité (candidateId + jobId)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(12)
    @DisplayName("TC-AREPO-12 : @PrePersist → appliedAt est défini automatiquement")
    void prePersist_appliedAtIsSetAutomatically() {
        Application app = Application.builder()
            .candidateId("cand-persist")
            .jobId("job-persist")
            .build();

        Application saved = applicationRepository.save(app);

        assertNotNull(saved.getAppliedAt(),
            "@PrePersist doit initialiser appliedAt");
    }
}
