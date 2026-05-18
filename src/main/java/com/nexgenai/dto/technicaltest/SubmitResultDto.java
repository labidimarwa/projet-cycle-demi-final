package com.nexgenai.dto.technicaltest;
import lombok.*;
import java.util.List;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SubmitResultDto {
    private double                  score;
    private int                     totalPoints;
    private int                     earnedPoints;
    private List<QuestionResultDto> questionsResults;
}