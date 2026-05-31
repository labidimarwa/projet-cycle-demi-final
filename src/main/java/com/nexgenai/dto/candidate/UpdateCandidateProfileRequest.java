package com.nexgenai.dto.candidate;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class UpdateCandidateProfileRequest {

    @Size(min = 2, max = 50)
    @Pattern(regexp = "^[^<>\"'&;]*$", message = "Invalid characters in first name")
    private String firstName;

    @Size(min = 2, max = 50)
    @Pattern(regexp = "^[^<>\"'&;]*$", message = "Invalid characters in last name")
    private String lastName;

    @Size(max = 100)
    @Pattern(regexp = "^[^<>\"'&;]*$", message = "Invalid characters in city")
    private String city;

    @Size(max = 150)
    private String currentPosition;
    private Integer yearsOfExperience;

    @Size(max = 100)
    private String educationLevel;

    @Size(max = 200)
    private String university;

    @Size(max = 2000)
    private String summary;

    @Pattern(regexp = "^(https?://.*)?$", message = "LinkedIn URL must start with http:// or https://")
    @Size(max = 300)
    private String linkedinUrl;

    @Pattern(regexp = "^(https?://.*)?$", message = "GitHub URL must start with http:// or https://")
    @Size(max = 300)
    private String githubUrl;

    @Pattern(regexp = "^(https?://.*)?$", message = "Portfolio URL must start with http:// or https://")
    @Size(max = 300)
    private String portfolioUrl;

    private String remoteWorkPreference;
    private String certifications;

    // Liste de liens personnalisés [{ title, url }]
    private List<LinkDTO> links;

    @Data
    public static class LinkDTO {
        private String title;
        private String url;
    }
}