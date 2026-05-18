package com.nexgenai.repository;

import com.nexgenai.model.WorkflowStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowStageRepository extends JpaRepository<WorkflowStage, String> {
    
    List<WorkflowStage> findByJobId(String jobId);
    List<WorkflowStage> findByAssessmentId(String assessmentId);
}