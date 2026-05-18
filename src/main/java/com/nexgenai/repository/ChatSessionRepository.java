package com.nexgenai.repository;

import com.nexgenai.model.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {
    List<ChatSession> findByCandidateId(String candidateId);
    Optional<ChatSession> findByCandidateIdAndJobId(String candidateId, String jobId);
}