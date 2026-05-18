package com.nexgenai.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserRequest {

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @NotBlank(message = "Role is required")
    private String role; // HR | TECH_EVALUATOR | ADMIN

    // HR fields
    private String department;
    private String position;
    private String employeeId;

    // TechEvaluator fields
    private String specialization;
    private String title;
    private Integer yearsOfExperience;
    private String expertiseLevel;
    private String currentCompany;
    private String linkedinUrl;
    private String githubUrl;
}