package com.nexgenai.repository;

import com.nexgenai.model.JobMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JobMatchRepository extends JpaRepository<JobMatch, String> {

    /** All matches for a given candidate (used in Discovery) */
    List<JobMatch> findByCandidateId(String candidateId);

    /** Single match for a (candidate, job) pair */
    Optional<JobMatch> findByCandidateIdAndJobId(String candidateId, String jobId);

    /**
     * FIX: All matches for a given job — used by HrService and
     * ApplicationStageProgressService to detect computed AI_SCREENING.
     */
    List<JobMatch> findByJobId(String jobId);

    /** Used by HrService to build the applicants list with scores */
    @Query("SELECT m FROM JobMatch m WHERE m.jobId = :jobId ORDER BY m.score DESC NULLS LAST")
    List<JobMatch> findByJobIdOrderByScoreDesc(@Param("jobId") String jobId);

    /** Used by JobService to compute avgMatchScore per job */
    @Query("SELECT AVG(m.score) FROM JobMatch m WHERE m.jobId = :jobId AND m.score IS NOT NULL")
    Double avgScoreByJobId(@Param("jobId") String jobId);

    /** Used by JobService to count applicants */
    long countByJobId(String jobId);

    void deleteByCandidateId(String candidateId);

    boolean existsByCandidateIdAndJobIdAndCvHash(String candidateId, String jobId, String cvHash);
}