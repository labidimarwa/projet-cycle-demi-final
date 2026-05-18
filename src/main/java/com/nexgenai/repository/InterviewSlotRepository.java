package com.nexgenai.repository;

import com.nexgenai.model.InterviewSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface InterviewSlotRepository extends JpaRepository<InterviewSlot, String> {
    List<InterviewSlot> findByInterviewId(String interviewId);
    List<InterviewSlot> findByAssigneeId(String assigneeId);
    List<InterviewSlot> findByCandidateId(String candidateId);
    int countByInterviewId(String interviewId);
    int countByInterviewIdAndStatus(String interviewId, InterviewSlot.SlotStatus status);
}