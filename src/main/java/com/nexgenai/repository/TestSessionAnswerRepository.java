package com.nexgenai.repository;

import com.nexgenai.model.TestSessionAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TestSessionAnswerRepository extends JpaRepository<TestSessionAnswer, String> {

    List<TestSessionAnswer> findBySessionId(String sessionId);

    Optional<TestSessionAnswer> findBySessionIdAndQuestionId(String sessionId, String questionId);
}
