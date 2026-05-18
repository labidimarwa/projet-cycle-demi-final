package com.nexgenai.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "candidate_id", nullable = false)
    private String candidateId;

    @Column(name = "job_id", nullable = false)
    private String jobId;

    // Full conversation history as JSON array
    @Column(name = "messages_json", columnDefinition = "TEXT")
    private String messagesJson;

    // How many questions asked so far
    @Column(name = "question_count")
    private Integer questionCount;

    // Final interview score (0-100) when done
    @Column(name = "interview_score")
    private Integer interviewScore;

    @Column(name = "is_done")
    private boolean done;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}