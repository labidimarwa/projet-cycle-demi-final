package com.nexgenai.repository;

import com.nexgenai.model.TestSessionAnswerDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TestSessionAnswerDecisionRepository extends JpaRepository<TestSessionAnswerDecision, String> {

    List<TestSessionAnswerDecision> findBySessionId(String sessionId);

    Optional<TestSessionAnswerDecision> findBySessionIdAndQuestionId(String sessionId, String questionId);

    void deleteBySessionIdAndQuestionId(String sessionId, String questionId);
}
