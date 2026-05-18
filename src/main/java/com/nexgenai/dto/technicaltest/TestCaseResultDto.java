package com.nexgenai.dto.technicaltest;
import lombok.*;
import java.util.List;
import java.util.Map;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TestCaseResultDto {
    private String  input;
    private String  expected;
    private String  actual;
    private boolean passed;
    private int     points;
    private int     earnedPoints;
    private long    executionMs;
    private long    memoryKb;
    private String  error;
}