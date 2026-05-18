package com.nexgenai.dto.test;

import com.nexgenai.model.enums.AssessmentType;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Basic payload describing a {@code TestSession} returned to the candidate
 * UI. Reflects the unified RH + TECHNICAL session model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestSessionDto {

    private String         sessionId;
    private AssessmentType type;            // RH | TECHNICAL
    private String         status;          // PENDING | NOT_STARTED | IN_PROGRESS | COMPLETED | EXPIRED

    // Linked assessment
    private String         testId;
    private String         testName;
    private String         jobTitle;

    // Question payload
    private int            totalQuestions;
    private List<QuestionDto> questions;

    // Timing
    /** Total duration in minutes (0 = no limit). */
    private int     timeLimit;
    /** Same value expressed in seconds (kept for technical-test UI). */
    private Integer timeLimitSeconds;
    private int     timeLeftSeconds;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    // Scoring
    private Integer score;
    private Integer earnedPoints;
    private Integer totalPoints;

    // Anti-cheat aggregates
    private Integer tabSwitches;
    private Integer copyPasteCount;
    private Integer windowBlurCount;
    private Integer devtoolsOpenCount;
    private Integer rightClickCount;
    private String  riskLevel;              // LOW | MEDIUM | HIGH
}
