package com.nexgenai.dto.technicaltest;

import lombok.*;
import java.util.List;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class QcmOptionDto {
    private String id;
    private String text;
    private int    points;
    // NB: 'correct' is NOT exposed to candidate
}
 