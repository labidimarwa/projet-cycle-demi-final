package com.nexgenai.dto.hr;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Full detail returned by GET /hr/jobs/{jobId}/candidates/{candidateId}
 * Contains: profile + match details + chat transcript + voice info.
 */
@Data
@Builder
public class CandidateApplicationDetailDTO {

    // ── Candidate profile ────────────────────────────────────────────────────
    private String  candidateId;
    private String  firstName;
    private String  lastName;
    private String  email;
    private String  city;
    private String  currentPosition;
    private Integer yearsOfExperience;
    private String  educationLevel;
    private String  university;
    private String  summary;
    private String  cvDisplayName;       // original file name for download link
    private String  linkedinUrl;
    private String  githubUrl;
    private String  portfolioUrl;
    private String  remoteWorkPreference;
    private String  certifications;

    // ── AI Match result ──────────────────────────────────────────────────────
    private MatchDetailDTO match;

    // ── Chat interview ────────────────────────────────────────────────────────
    private ChatDetailDTO chat;

    // ── Application meta ─────────────────────────────────────────────────────
    private LocalDateTime appliedAt;

    // ────────────────────────────────────────────────────────────────────────
    @Data
    @Builder
    public static class MatchDetailDTO {
        private Integer score;
        private String  verdict;
        private String  resume;
        private String  skillsMatched;   // raw JSON string — parsed on frontend
        private String  skillsMissing;   // raw JSON string
        private String  dimensionsJson;  // raw JSON string
        private LocalDateTime computedAt;
    }

    // ────────────────────────────────────────────────────────────────────────
    @Data
    @Builder
    public static class ChatDetailDTO {
        private String  sessionId;
        private boolean done;
        private Integer score;           // final interview score
        private Integer questionCount;
        private List<MessageDTO> messages;
        private LocalDateTime startedAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    public static class MessageDTO {
        private String role;    // "user" | "assistant" | "system"
        private String content;
    }
}