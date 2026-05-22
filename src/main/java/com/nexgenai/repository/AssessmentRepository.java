package com.nexgenai.repository;

import com.nexgenai.model.Assessment;
import com.nexgenai.model.enums.AssessmentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the unified {@link Assessment} entity. Merges the queries
 * previously split between {@code AssessmentRepository} and
 * {@code JobTestRepository}.
 */
@Repository
public interface AssessmentRepository extends JpaRepository<Assessment, String> {

    // ── Basic look-ups ───────────────────────────────────────────────────────

    @Override
    Optional<Assessment> findById(String id);

    Optional<Assessment> findByLinkId(String linkId);

    Optional<Assessment> findByWorkflowStageId(String workflowStageId);

    List<Assessment> findByJobId(String jobId);

    List<Assessment> findByJobIdAndType(String jobId, AssessmentType type);

    List<Assessment> findByType(AssessmentType type);

    List<Assessment> findByHrId(String hrId);
    List<Assessment> findByJobIdAndAssigneeId(String jobId, String assigneeId);

    // ── Fetch-graph variants (kept from JobTestRepository) ───────────────────

    @Query("SELECT DISTINCT a FROM Assessment a LEFT JOIN FETCH a.job")
    List<Assessment> findAllWithJob();

    /**
     * Pass 1: assessment + job + themes + themeModels + model + dimensions.
     */
    @Query("""
        SELECT DISTINCT a FROM Assessment a
        LEFT JOIN FETCH a.job
        LEFT JOIN FETCH a.themes th
        LEFT JOIN FETCH th.themeModels tm
        LEFT JOIN FETCH tm.model m
        LEFT JOIN FETCH m.dimensions
        WHERE a.id = :id
        """)
    Optional<Assessment> findByIdWithThemes(@Param("id") String id);

    /**
     * Pass 2: themeModels + questions + options. Called separately from the
     * service inside the same transaction to avoid Cartesian fetches.
     */
    @Query("""
        SELECT DISTINCT a FROM Assessment a
        LEFT JOIN FETCH a.themes th
        LEFT JOIN FETCH th.themeModels tm
        LEFT JOIN FETCH tm.questions q
        LEFT JOIN FETCH q.options
        WHERE a.id = :id
        """)
    Optional<Assessment> findByIdWithQuestions(@Param("id") String id);
}
