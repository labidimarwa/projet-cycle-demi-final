package com.nexgenai.dto.test;

import lombok.*;

import java.util.List;

/**
 * Unified question payload — supports both QCM and PROBLEM_SOLVING
 * questions, mirroring the merged {@code Question} entity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionDto {

    private String id;

    /** QCM | PROBLEM_SOLVING. */
    private String kind;

    /** RADIO | CHECKBOX | LIKERT | RANKING (QCM sub-type). */
    private String questionType;

    // Content
    private String title;
    private String text;
    private String statement;
    private String imageUrl;

    private Integer points;
    private int     orderIndex;

    // QCM
    private List<OptionDto> options;
    private List<Integer>   likertPoints;

    // PROBLEM_SOLVING
    private String          complexity;
    private Double          timeLimit;
    private Integer         memoryLimit;
    private List<TestCaseDto> testCases;

    /**
     * Lightweight inline test-case representation. Kept here so that the
     * basic {@code dto/test} package is self-contained and does not depend
     * on the heavier {@code dto/technicaltest} payloads.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestCaseDto {
        private String  input;
        private String  output;
        private Integer points;
        private Boolean visible;
    }
}
