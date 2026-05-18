package com.nexgenai.repository;
 
import com.nexgenai.model.ApplicationStageProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
 
import java.util.List;
 
@Repository
public interface ApplicationStageProgressRepository extends JpaRepository<ApplicationStageProgress, String> {
 
    /** All stages for a given (candidate, job), ordered by stage_order */
    @Query("SELECT p FROM ApplicationStageProgress p WHERE p.candidateId = :candidateId AND p.jobId = :jobId ORDER BY p.stageOrder ASC")
    List<ApplicationStageProgress> findByCandidateAndJob(
            @Param("candidateId") String candidateId,
            @Param("jobId") String jobId);
 
    /** Used by ApplicationService to seed rows when candidate applies */
    boolean existsByCandidateIdAndJobId(String candidateId, String jobId);
 
    /** Count completed stages — used for progress % */
    @Query("SELECT COUNT(p) FROM ApplicationStageProgress p WHERE p.candidateId = :candidateId AND p.jobId = :jobId AND p.status = 'COMPLETED'")
    long countCompleted(@Param("candidateId") String candidateId, @Param("jobId") String jobId);
    
    List<ApplicationStageProgress> findByCandidateIdAndJobId(String candidateId, String jobId);
    
 // Ajouter cette méthode
    List<ApplicationStageProgress> findByJobIdAndStageType(String jobId, String stageType);

}
 