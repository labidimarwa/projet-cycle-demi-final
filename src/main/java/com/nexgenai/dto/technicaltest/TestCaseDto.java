package com.nexgenai.dto.technicaltest;
 
import lombok.*;
import java.util.List;
import java.util.Map;


@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TestCaseDto {
    private String  input;
    private String  output;
    private int     points;
    private boolean isVisible;
}