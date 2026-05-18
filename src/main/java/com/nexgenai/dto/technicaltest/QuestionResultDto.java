package com.nexgenai.dto.technicaltest;
import lombok.*;
import java.util.List;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class QuestionResultDto {
    private String questionId;
    private String title;
    private String type;
    private int    earnedPoints;
    private int    maxPoints;
}