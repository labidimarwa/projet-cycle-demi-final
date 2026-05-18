package com.nexgenai.dto.technicaltest;

import lombok.*;
import java.util.List;
import java.util.Map;


//-- RunCodeRequest.java --
@Data @NoArgsConstructor @AllArgsConstructor
public class RunCodeRequest {
   private String       code;
   private String       language;
   private List<TestCasePayload> testCases;
   private String       questionId;
   private String       sessionId;

   @Data @NoArgsConstructor @AllArgsConstructor
   public static class TestCasePayload {
       private String  input;
       private String  output;
       private int     points;
       private boolean isVisible;
   }
}
