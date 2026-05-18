package com.nexgenai.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserListResponse {
    private String id;
    private String firstName;
    private String lastName;
    private String email;
    private String userType;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime lastLogin;
}