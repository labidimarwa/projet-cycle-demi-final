package com.nexgenai.repository;

import com.nexgenai.model.TestSession;
import com.nexgenai.model.enums.AssessmentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the unified {@link TestSession} entity. Merges the queries
 * previously split between {@code TestSessionRepository} (RH) and
 * {@code TechnicalSessionRepository} (technical).
 *
 * <p>The schema field {@code jobTest} was renamed to {@code assessment} as
 * part of the Phase 1 refactor — finder method names have been updated
 * accordingly.</p>
 */
@Repository
public interface TestSessionRepository extends JpaRepository<TestSession, String> {

    // ── By assessment ────────────────────────────────────────────────────────

    @Query("SELECT s FROM TestSession s LEFT JOIN FETCH s.candidate WHERE s.assessment.id = :assessmentId")
    List<TestSession> findByAssessmentId(@Param("assessmentId") String assessmentId);

    List<TestSession> findByAssessmentIdAndType(String assessmentId, AssessmentType type);

    int countByAssessmentId(String assessmentId);

    // ── By candidate + assessment ────────────────────────────────────────────

    @Query("""
        SELECT s FROM TestSession s
        LEFT JOIN FETCH s.candidate
        WHERE s.candidate.id = :candidateId
          AND s.assessment.id = :assessmentId
        """)
    Optional<TestSession> findByCandidateIdAndAssessmentId(
        @Param("candidateId") String candidateId,
        @Param("assessmentId") String assessmentId
    );

    // ── By candidate ─────────────────────────────────────────────────────────

    List<TestSession> findByCandidateId(String candidateId);

    List<TestSession> findByCandidateIdAndType(String candidateId, AssessmentType type);

    // ── By type ──────────────────────────────────────────────────────────────

    List<TestSession> findByType(AssessmentType type);

    // ── By status ─────────────────────────────────────────────────────────────

    List<TestSession> findByStatus(TestSession.SessionStatus status);
}
