package com.nexgenai.repository;

import com.nexgenai.model.TestSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link TestSubmission}. Finder method names were renamed
 * from {@code jobTestId} to {@code assessmentId} as part of the Phase 1
 * model refactor.
 */
@Repository
public interface TestSubmissionRepository extends JpaRepository<TestSubmission, String> {

    List<TestSubmission> findByAssessmentId(String assessmentId);

    Optional<TestSubmission> findByAssessmentIdAndCandidateId(String assessmentId, String candidateId);

    long countByAssessmentId(String assessmentId);
}
