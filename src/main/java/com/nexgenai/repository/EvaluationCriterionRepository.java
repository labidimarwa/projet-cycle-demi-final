package com.nexgenai.repository;
 
import com.nexgenai.model.EvaluationCriterion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
 
@Repository
public interface EvaluationCriterionRepository extends JpaRepository<EvaluationCriterion, String> {
    List<EvaluationCriterion> findByInterviewIdOrderByCriterionOrderAsc(String interviewId);
}