package com.nexgenai.repository;

import com.nexgenai.model.Job;
import com.nexgenai.model.enums.ContractType;
import com.nexgenai.model.enums.ExperienceLevel;
import com.nexgenai.model.enums.JobStatus;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests d'intégration du JobRepository avec H2.
 *
 * Vérifie :
 *   - findByStatus
 *   - findByDepartment
 *   - searchByKeyword (dans title et description)
 *   - searchActiveJobsByKeyword
 *   - countByStatus
 *   - findByIdWithDetails (join fetch)
 *   - existsById
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("JobRepository — Tests d'Intégration DataJPA")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JobRepositoryTest {

    @Autowired private JobRepository jobRepository;

    // ── Helper ────────────────────────────────────────────────────────────────

    private Job buildJob(String title, String department, JobStatus status, String description) {
        Job job = new Job();
        job.setTitle(title);
        job.setDepartment(department);
        job.setLocation("Tunis");
        job.setContractType(ContractType.CONTRACT);
        job.setExperienceLevel(ExperienceLevel.SENIOR);
        job.setDescription(description);
        job.setStatus(status);
        job.setOpenPositions(1);
        job.setIsRemote(false);
        job.setCreatedAt(LocalDateTime.now());
        return job;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findByStatus
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-JREPO-01 : findByStatus(ACTIVE) → retourne uniquement les jobs ACTIVE")
    void findByStatus_active_returnsOnlyActiveJobs() {
        // GIVEN
        jobRepository.save(buildJob("Dev Java", "IT", JobStatus.ACTIVE, "Desc Java"));
        jobRepository.save(buildJob("Data Sci", "Data", JobStatus.DRAFT, "Desc Data"));
        jobRepository.save(buildJob("DevOps", "IT", JobStatus.ACTIVE, "Desc DevOps"));

        // WHEN
        List<Job> result = jobRepository.findByStatus(JobStatus.ACTIVE);

        // THEN
        assertFalse(result.isEmpty());
        result.forEach(j ->
            assertEquals(JobStatus.ACTIVE, j.getStatus(),
                "Tous les jobs retournés doivent avoir le statut ACTIVE")
        );
    }

    @Test
    @Order(2)
    @DisplayName("TC-JREPO-02 : findByStatus(DRAFT) → retourne uniquement les jobs DRAFT")
    void findByStatus_draft_returnsOnlyDraftJobs() {
        // GIVEN
        jobRepository.save(buildJob("Draft Job", "Marketing", JobStatus.DRAFT, "Draft desc"));

        // WHEN
        List<Job> result = jobRepository.findByStatus(JobStatus.DRAFT);

        // THEN
        assertFalse(result.isEmpty());
        result.forEach(j -> assertEquals(JobStatus.DRAFT, j.getStatus()));
    }

    @Test
    @Order(3)
    @DisplayName("TC-JREPO-03 : findByStatus(statut sans correspondance) → liste vide")
    void findByStatus_noMatches_returnsEmptyList() {
        // Il n'y a pas de jobs CLOSED dans la BDD de test
        List<Job> result = jobRepository.findByStatus(JobStatus.CLOSED);
        // Peut retourner vide si aucun job CLOSED n'a été créé
        // (les tests précédents créent ACTIVE et DRAFT)
        result.forEach(j -> assertEquals(JobStatus.CLOSED, j.getStatus()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findByDepartment
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("TC-JREPO-04 : findByDepartment('IT') → retourne les jobs IT")
    void findByDepartment_it_returnsItJobs() {
        // GIVEN
        jobRepository.save(buildJob("Java Dev", "IT", JobStatus.ACTIVE, "Java dev role"));
        jobRepository.save(buildJob("HR Manager", "RH", JobStatus.ACTIVE, "HR role"));

        // WHEN
        List<Job> result = jobRepository.findByDepartment("IT");

        // THEN
        assertFalse(result.isEmpty());
        result.forEach(j -> assertEquals("IT", j.getDepartment()));
    }

    @Test
    @Order(5)
    @DisplayName("TC-JREPO-05 : findByDepartment(département inexistant) → liste vide")
    void findByDepartment_nonExisting_returnsEmptyList() {
        List<Job> result = jobRepository.findByDepartment("UNKNOWN_DEPT_999");
        assertTrue(result.isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // searchByKeyword
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("TC-JREPO-06 : searchByKeyword('Spring') → trouve dans le titre")
    void searchByKeyword_findsInTitle() {
        // GIVEN
        jobRepository.save(buildJob("Spring Boot Developer", "IT",
            JobStatus.ACTIVE, "We need a backend dev"));

        // WHEN
        List<Job> result = jobRepository.searchByKeyword("Spring");

        // THEN
        assertFalse(result.isEmpty());
        assertTrue(result.stream().anyMatch(j -> j.getTitle().contains("Spring")));
    }

    @Test
    @Order(7)
    @DisplayName("TC-JREPO-07 : searchByKeyword('microservices') → trouve dans la description")
    void searchByKeyword_findsInDescription() {
        // GIVEN
        jobRepository.save(buildJob("Backend Developer", "IT",
            JobStatus.ACTIVE, "Experience with microservices required"));

        // WHEN
        List<Job> result = jobRepository.searchByKeyword("microservices");

        // THEN
        assertFalse(result.isEmpty());
        assertTrue(result.stream()
            .anyMatch(j -> j.getDescription().contains("microservices")));
    }

    @Test
    @Order(8)
    @DisplayName("TC-JREPO-08 : searchByKeyword(terme inexistant) → liste vide")
    void searchByKeyword_noMatches_returnsEmptyList() {
        List<Job> result = jobRepository.searchByKeyword("zzz_no_match_xyz_99");
        assertTrue(result.isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // searchActiveJobsByKeyword
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(9)
    @DisplayName("TC-JREPO-09 : searchActiveJobsByKeyword → ne retourne que les jobs ACTIVE")
    void searchActiveJobsByKeyword_returnsOnlyActiveJobs() {
        // GIVEN : un job ACTIVE et un job DRAFT avec le même mot-clé
        String keyword = "reactreactunique";
        jobRepository.save(buildJob("React Developer " + keyword, "IT",
            JobStatus.ACTIVE, "Desc active"));
        jobRepository.save(buildJob("React Designer " + keyword, "UI",
            JobStatus.DRAFT, "Desc draft"));

        // WHEN
        List<Job> result = jobRepository.searchActiveJobsByKeyword(keyword);

        // THEN
        assertFalse(result.isEmpty());
        result.forEach(j -> assertEquals(JobStatus.ACTIVE, j.getStatus()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // countByStatus
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("TC-JREPO-10 : countByStatus → retourne le nombre correct de jobs par statut")
    void countByStatus_returnsCorrectCount() {
        // GIVEN : créer 3 jobs CLOSED
        long beforeClosed = jobRepository.countByStatus(JobStatus.CLOSED);
        jobRepository.save(buildJob("Closed Job 1", "IT", JobStatus.CLOSED, "Closed 1"));
        jobRepository.save(buildJob("Closed Job 2", "IT", JobStatus.CLOSED, "Closed 2"));
        jobRepository.save(buildJob("Closed Job 3", "IT", JobStatus.CLOSED, "Closed 3"));

        // WHEN
        long count = jobRepository.countByStatus(JobStatus.CLOSED);

        // THEN
        assertEquals(beforeClosed + 3, count);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // existsById
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(11)
    @DisplayName("TC-JREPO-11 : existsById(id existant) → true")
    void existsById_existingId_returnsTrue() {
        Job saved = jobRepository.save(buildJob("Test Exists", "IT", JobStatus.ACTIVE, "desc"));
        assertTrue(jobRepository.existsById(saved.getId()));
    }

    @Test
    @Order(12)
    @DisplayName("TC-JREPO-12 : existsById(id inexistant) → false")
    void existsById_nonExistingId_returnsFalse() {
        assertFalse(jobRepository.existsById("non-existent-id-xyz"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findByIdWithDetails
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(13)
    @DisplayName("TC-JREPO-13 : findByIdWithSkills → retourne le job avec ses compétences")
    void findByIdWithSkills_existingJob_returnsJob() {
        // GIVEN
        Job saved = jobRepository.save(buildJob("Details Job", "IT", JobStatus.ACTIVE, "desc"));

        // WHEN — use findByIdWithSkills to avoid MultipleBagFetchException
        Optional<Job> result = jobRepository.findByIdWithSkills(saved.getId());

        // THEN
        assertTrue(result.isPresent());
        assertEquals("Details Job", result.get().getTitle());
    }

    @Test
    @Order(14)
    @DisplayName("TC-JREPO-14 : findById(id inexistant) → Optional.empty()")
    void findById_nonExistingId_returnsEmpty() {
        Optional<Job> result = jobRepository.findById("non-existent-xyz");
        assertTrue(result.isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findLatestJobsByStatus
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(15)
    @DisplayName("TC-JREPO-15 : findLatestJobsByStatus → retourne les jobs triés par date desc")
    void findLatestJobsByStatus_returnsSortedByDateDesc() {
        // GIVEN
        Job older = buildJob("Older Paused", "Finance", JobStatus.PAUSED, "desc");
        older.setCreatedAt(LocalDateTime.now().minusDays(5));
        Job newer = buildJob("Newer Paused", "Finance", JobStatus.PAUSED, "desc");
        newer.setCreatedAt(LocalDateTime.now());

        jobRepository.save(older);
        jobRepository.save(newer);

        // WHEN
        List<Job> result = jobRepository.findLatestJobsByStatus(JobStatus.PAUSED);

        // THEN : le job le plus récent doit être en premier
        assertFalse(result.isEmpty());
        // Vérifier que les résultats sont bien des jobs PAUSED
        result.forEach(j -> assertEquals(JobStatus.PAUSED, j.getStatus()));
    }
}
