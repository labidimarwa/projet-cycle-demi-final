package com.nexgenai.dto.technicaltest;
import lombok.*;
import java.util.List;
import java.util.Map;


@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AntiCheatReportDto {
    private String  sessionId;
    private String  candidateName;
    private String  testName;
    private int     score;
    private int     totalPoints;
    private int     earnedPoints;
    private String  riskLevel;
    private int     tabSwitchCount;
    private int     pasteCount;
    private int     blurCount;
    private int     devToolsAttempts;
    private int     totalEvents;
    private List<AntiCheatEventDto>  events;
    private List<QuestionResult>     questionsResults;  // ★ AJOUTER
 
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class QuestionResult {              // ★ AJOUTER
        private String questionId;
        private String title;
        private String type;
        private int    earnedPoints;
        private int    maxPoints;
    }
}