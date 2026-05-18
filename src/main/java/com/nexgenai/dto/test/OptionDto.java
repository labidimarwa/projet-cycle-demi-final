package com.nexgenai.dto.test;

import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OptionDto {
    private String id;
    private String text;
    private int    orderIndex;
    // dimensionId and points NOT sent to client (anti-cheat)
}