package com.nexgenai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoginResponse {
    private String token;
    private String refreshToken;
    private String id;
    private String email;
    private String firstName;
    private String lastName;
    private String userType;
    private long expiresIn;
    
    // Candidate specific fields
    private String currentPosition;
    private Integer yearsOfExperience;
    private String educationLevel;
    
    // HR specific fields
    private String department;
    private String position;
    
    // TechEvaluator specific fields
    private String specialization;
    private String expertiseLevel;
    private String title;
}