package com.nexgenai.dto.jobtest;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

public class JobTestDtos {

    private JobTestDtos() {}

    // ══════════════════════════════════════════════════════════════════
    // REQUESTS
    // ══════════════════════════════════════════════════════════════════

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateTestRequest {
        private String jobId;
        private String name;
        private String description;
        private String status;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UpdateTestRequest {
        private String name;
        private String description;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateThemeRequest {
        private String name;
        private String category;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class AddModelRequest {
        private String  modelId;
        private Integer weight;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UpdateWeightRequest {
        private Integer weight;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateQuestionRequest {
        private String text;
        private String type;
        private List<OptionRequest> options;

        @Data @NoArgsConstructor @AllArgsConstructor
        public static class OptionRequest {
            private String  text;
            private String  dimensionId;
            private Integer points;
            private Boolean correct;
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SaveFullTestRequest {
        private String jobId;
        private String name;
        private String description;
        private String status;
        private List<ThemePayload> themes;

        @Data @NoArgsConstructor @AllArgsConstructor
        public static class ThemePayload {
            private String id;
            private String name;
            private String type;        // PROBLEM_SOLVING | QCM
            private int    orderIndex;
            private List<QuestionPayload> questions;
        }

        @Data @NoArgsConstructor @AllArgsConstructor
        public static class QuestionPayload {
            private String  id;
            private String  title;
            private String  statement;
            private Integer points;
            private int     orderIndex;

            private String  complexity;
            private Double  timeLimit;
            private Integer memoryLimit;
            private List<TestCasePayload> testCases;
            private List<String> supportedLangs;

            private String questionType;           // RADIO | CHECKBOX | LIKERT | RANKING
            private List<QcmOptionPayload> options;
            private List<Integer> likertPoints;
        }

        @Data @NoArgsConstructor @AllArgsConstructor
        public static class QcmOptionPayload {
            private String  text;
            private boolean correct;
            private Integer points;
        }

        @Data @NoArgsConstructor @AllArgsConstructor
        public static class TestCasePayload {
            private String  input;
            private String  output;
            private Integer points;
            private Boolean visible;
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateModelRequest {
        private String name;
        private String description;
        private String scoringType;
        private List<DimensionRequest> dimensions;

        @Data @NoArgsConstructor @AllArgsConstructor
        public static class DimensionRequest {
            private String name;
            private String code;
            private String color;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // RESPONSES — BASE
    // ══════════════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class JobTestResponse {
        private String id;
        private String jobId;
        private String jobTitle;
        private String department;
        private String name;
        private String description;
        private String status;
        private String type;            // TECHNICAL | RH
        private int    themesCount;
        private int    questionsCount;
        private int    candidatesCount;
        private int    completionRate;
        private LocalDateTime createdAt;
        private List<ThemeResponse> themes;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ThemeResponse {
        private String id;
        private String name;
        private String category;
        private String type;       // PROBLEM_SOLVING | QCM (front-facing)
        private int    orderIndex;
        private List<ThemeModelResponse>     models;
        private List<SimpleQuestionResponse> questions;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ThemeModelResponse {
        private String id;
        private String modelId;
        private String modelName;
        private String modelDescription;
        private String scoringType;
        private int    weight;
        private List<DimensionResponse> dimensions;
        private List<QuestionResponse>  questions;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class QuestionResponse {
        private String id;
        private String text;
        private String imageUrl;
        private String type;
        private int    orderIndex;
        private List<OptionResponse> options;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SimpleQuestionResponse {
        private String  id;
        private String  title;
        private String  statement;
        private Integer points;
        private int     orderIndex;
        private String  type;           // PROBLEM_SOLVING | QCM

        private String  complexity;
        private Double  timeLimit;
        private Integer memoryLimit;
        private List<TestCaseResponse>  testCases;
        private List<String>            supportedLangs;

        private String                  questionType;  // RADIO|CHECKBOX|LIKERT|RANKING
        private List<QcmOptionResponse> options;
        private List<Integer>           likertPoints;
        private String                  imageUrl;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class QcmOptionResponse {
        private String  text;
        private boolean correct;
        private Integer points;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TestCaseResponse {
        private String  input;
        private String  output;
        private Integer points;
        private Boolean visible;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OptionResponse {
        private String  id;
        private String  text;
        private String  dimensionId;
        private Integer points;
        private int     orderIndex;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DimensionResponse {
        private String id;
        private String name;
        private String code;
        private String color;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ModelResponse {
        private String  id;
        private String  name;
        private String  description;
        private String  scoringType;
        private boolean builtIn;
        private List<DimensionResponse> dimensions;
    }

    // ══════════════════════════════════════════════════════════════════
    // RESPONSES — JOBS WITH TESTS
    // ══════════════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class JobWithTestsResponse {
        private String                    id;
        private String                    title;
        private String                    department;
        private String                    location;
        private String                    status;
        private List<TestSummaryResponse> tests;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TestSummaryResponse {
        private String  id;
        private String  name;
        private String  description;
        private String  status;
        private int     themesCount;
        private int     questionsCount;
        private int     candidatesCount;
        private int     completionRate;
        private String  createdAt;
        private String  source;       // "JOB_TEST" | "WORKFLOW_STAGE"
        private String  stageType;    // "RH_TEST" | "TECHNICAL_TEST" | null
        private String  assignedTo;
        private String  assigneeId;
    }

    // ══════════════════════════════════════════════════════════════════
    // RESPONSES — CANDIDATE SCORE BREAKDOWN
    // ══════════════════════════════════════════════════════════════════

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DimensionScoreResponse {
        private String dimensionId;
        private String dimensionName;
        private String dimensionCode;
        private String color;
        private int    score;
        private int    maxScore;
        private double percentage;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ThemeModelResultResponse {
        private String themeModelId;
        private String modelName;
        private String modelId;
        private String scoringType;
        private int    weight;
        private int    totalScore;
        private int    maxScore;
        private double percentage;
        private int    questionsCount;
        private int    answeredCount;
        private List<DimensionScoreResponse> dimensions;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ThemeResultResponse {
        private String themeId;
        private String themeName;
        private String themeCategory;
        private int    totalScore;
        private int    maxScore;
        private double percentage;
        private List<ThemeModelResultResponse> models;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AnswerOptionResponse {
        private String  optionId;
        private String  optionText;
        private String  dimensionId;
        private String  dimensionName;
        private int     points;
        private boolean selected;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ModelAnswersResponse {
        private String themeModelId;
        private String modelName;
        private List<QuestionAnswerResponse> questions;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CandidateTestResultResponse {
        private String  submissionId;
        private String  candidateId;
        private String  candidateName;
        private String  candidateEmail;
        private String  candidateAvatar;
        private String  jobTitle;
        private String  testName;
        private String  testId;
        private String  status;
        private String  startedAt;
        private String  completedAt;
        private Integer durationMinutes;
        private int     totalScore;
        private int     maxScore;
        private double  percentage;
        private Integer rank;
        private List<ThemeResultResponse>  themes;
        private List<ModelAnswersResponse> modelAnswers;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CandidateSummaryResponse {
        private String  candidateId;
        private String  candidateName;
        private String  candidateEmail;
        private String  candidateAvatar;
        private String  status;
        private String  completedAt;
        private Double  percentage;
        private Integer totalScore;
        private Integer maxScore;
        private Integer rank;
        private String  topDimension;
        private String  topDimensionColor;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TestCandidatesResponse {
        private String testId;
        private String testName;
        private String jobTitle;
        private String department;
        private int    totalInvited;
        private int    completed;
        private int    inProgress;
        private int    notStarted;
        private double avgScore;
        private List<CandidateSummaryResponse> candidates;
    }

    // ══════════════════════════════════════════════════════════════════
    // RESPONSES — DETAILED CANDIDATE ANSWERS  (new)
    // ══════════════════════════════════════════════════════════════════

    /**
     * One option inside a QCM question, enriched with whether the candidate selected it.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class QcmOptionAnswerResponse {
        private String  id;
 
        /** Primary field used by Tech answers */
        private String  text;
 
        /**
         * Alias for `text` — populated equal to `text` so Angular templates that
         * reference opt.optionText (RH answers tab) always get the option label.
         */
        private String  optionText;
 
        private boolean isCorrect;
        private int     points;
        private boolean selected;
        private String  dimensionId;
        private String  dimensionName;
    }

    /**
     * One test-case result for a PROBLEM_SOLVING question.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TestCaseAnswerResponse {
        private String  input;
        private String  expectedOutput;
        private String  actualOutput;
        private boolean passed;
        private int     points;
        private int     earnedPoints;
        private Long    executionMs;
        private boolean isVisible;
    }

    /**
     * Full detail of one question with the candidate's answer included.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class QuestionAnswerResponse {
        private String  questionId;
        private String  title;
        private String  statement;
        private String  type;           // PROBLEM_SOLVING | QCM
        private String  questionType;   // RADIO | CHECKBOX | LIKERT | RANKING
        private String  complexity;
        private Double  timeLimit;
        private Integer memoryLimit;
        private int     points;
        private int     earnedPoints;
        private int     orderIndex;
        private String  imageUrl;

        // QCM fields
        private List<QcmOptionAnswerResponse> options;
        private List<Integer>                 likertPoints;
        private Integer                       selectedLikert;

        // PROBLEM_SOLVING fields
        private String                       submittedCode;
        private String                       submittedLanguage;
        private List<TestCaseAnswerResponse> testCases;

        // Evaluator per-answer decision
        private String  answerDecision;  // APPROVED | REJECTED | null
        private String  answerNote;
        private Integer manualPoints;
    }

    /**
     * All questions of one theme with their candidate answers.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ThemeAnswersResponse {
        private String themeId;
        private String themeName;
        private String themeCategory;
        private int    totalScore;
        private int    maxScore;
        private double percentage;
        private List<QuestionAnswerResponse> questions;
    }

    /**
     * Top-level response for GET /job-tests/{testId}/candidates/{candidateId}/answers.
     * Contains both the score summary AND the per-question detailed answers.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CandidateFullResultResponse {
        // Identity / summary (mirrors CandidateTestResultResponse)
        private String  submissionId;
        private String  candidateId;
        private String  candidateName;
        private String  candidateEmail;
        private String  jobTitle;
        private String  testName;
        private String  testId;
        private String  status;
        private String  startedAt;
        private String  completedAt;
        private Integer durationMinutes;
        private int     totalScore;
        private int     maxScore;
        private double  percentage;
        private Integer rank;

        // Score breakdown by theme/model/dimension
        private List<ThemeResultResponse> themes;

        // Per-question answers (new)
        private List<ThemeAnswersResponse> themeAnswers;
    }
}