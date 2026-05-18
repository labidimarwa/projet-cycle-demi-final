package com.nexgenai.repository;

import com.nexgenai.model.CandidateAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CandidateAnswerRepository extends JpaRepository<CandidateAnswer, String> {

    List<CandidateAnswer> findBySubmissionId(String submissionId);

    Optional<CandidateAnswer> findBySubmissionIdAndQuestionId(String submissionId, String questionId);

    void deleteBySubmissionId(String submissionId);
}