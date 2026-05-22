package com.nexgenai.repository;

import com.nexgenai.model.Interview;
import com.nexgenai.model.enums.StageType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface InterviewRepository extends JpaRepository<Interview, String> {
    List<Interview> findByAssigneeId(String assigneeId);
    List<Interview> findByJobId(String jobId);
    Optional<Interview> findByWorkflowStageId(String workflowStageId);
    List<Interview> findByJobIdAndStageType(String jobId, StageType stageType);
    void deleteAllByJobId(String jobId);

    @Query("SELECT i FROM Interview i WHERE i.id = :interviewId")
    Optional<Interview> findByInterviewId(@Param("interviewId") String interviewId);

    @Query("SELECT i FROM Interview i WHERE i.jobId = :jobId AND i.stageType IN :types")
    List<Interview> findByJobIdAndStageTypeIn(
            @Param("jobId") String jobId,
            @Param("types") List<StageType> types);

    @Query("SELECT i FROM Interview i WHERE i.jobId = :jobId AND i.phaseStatus = :status")
    List<Interview> findByJobIdAndPhaseStatus(
            @Param("jobId") String jobId,
            @Param("status") String status);
}
