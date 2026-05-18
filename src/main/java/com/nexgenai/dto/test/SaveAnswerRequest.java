package com.nexgenai.dto.test;

import lombok.*;
import java.util.List;
@Data @NoArgsConstructor @AllArgsConstructor
public class SaveAnswerRequest {
    private String       sessionId;
    private String       questionId;
    private List<String> optionIds;
}