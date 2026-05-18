package com.nexgenai.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserResponse {
    private String id;
    private String firstName;
    private String lastName;
    private String email;
    private String role;
    private String message;
}