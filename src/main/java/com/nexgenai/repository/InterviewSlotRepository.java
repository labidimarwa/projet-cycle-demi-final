package com.nexgenai.repository;

import com.nexgenai.model.InterviewSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Repository
public interface InterviewSlotRepository extends JpaRepository<InterviewSlot, String> {
    List<InterviewSlot> findByInterviewId(String interviewId);
    List<InterviewSlot> findByAssigneeId(String assigneeId);
    List<InterviewSlot> findByCandidateId(String candidateId);
    int countByInterviewId(String interviewId);
    int countByInterviewIdAndStatus(String interviewId, InterviewSlot.SlotStatus status);

    /** All slots for a batch of interview IDs (used to find occupied dates across phases). */
    List<InterviewSlot> findByInterviewIdIn(List<String> interviewIds);

    /** Distinct dates (as date part of slotStart) occupied by a set of interviews. */
    @Query("SELECT DISTINCT CAST(s.slotStart AS date) FROM InterviewSlot s WHERE s.interviewId IN :interviewIds")
    List<LocalDate> findOccupiedDatesByInterviewIds(@Param("interviewIds") List<String> interviewIds);

    /** Count slots for an interview that are still SCHEDULED (not yet evaluated). */
    @Query("SELECT COUNT(s) FROM InterviewSlot s WHERE s.interviewId = :interviewId AND s.status = 'SCHEDULED'")
    long countScheduledByInterviewId(@Param("interviewId") String interviewId);

    /** Find slot by candidate + interview to avoid duplicates. */
    @Query("SELECT s FROM InterviewSlot s WHERE s.interviewId = :interviewId AND s.candidateId = :candidateId")
    List<InterviewSlot> findByInterviewIdAndCandidateId(
            @Param("interviewId") String interviewId,
            @Param("candidateId") String candidateId);
}
