package com.nexgenai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterResponse {
    private String token;
    private String refreshToken;
    private String id;
    private String email;
    private String firstName;
    private String lastName;
    private String userType;
    private long   expiresIn;
}