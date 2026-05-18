package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "interview_slots")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "interview_id", nullable = false)
    private String interviewId;

    @Column(name = "candidate_id", nullable = false)
    private String candidateId;

    @Column(name = "candidate_name")
    private String candidateName;

    @Column(name = "candidate_email")
    private String candidateEmail;

    @Column(name = "assignee_id")
    private String assigneeId;

    @Column(name = "assignee_name")
    private String assigneeName;

    @Column(name = "slot_start")
    private LocalDateTime slotStart;

    @Column(name = "slot_end")
    private LocalDateTime slotEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private SlotStatus status = SlotStatus.SCHEDULED;

    /** JSON evaluation result filled after the interview */
    @Column(name = "evaluation_result_json", columnDefinition = "TEXT")
    private String evaluationResultJson;

    @Column(name = "overall_score")
    private Integer overallScore;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "decision")
    private String decision; // ACCEPTED / REJECTED / PENDING

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) status = SlotStatus.SCHEDULED;
    }

    public enum SlotStatus {
        SCHEDULED, COMPLETED, CANCELLED, NO_SHOW
    }
}