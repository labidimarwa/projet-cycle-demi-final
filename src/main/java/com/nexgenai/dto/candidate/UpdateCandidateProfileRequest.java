package com.nexgenai.dto.candidate;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class UpdateCandidateProfileRequest {

    @Size(min = 2, max = 50)
    private String firstName;

    @Size(min = 2, max = 50)
    private String lastName;

    private String city;
    private String currentPosition;
    private Integer yearsOfExperience;
    private String educationLevel;
    private String university;

    @Size(max = 2000)
    private String summary;

    private String linkedinUrl;
    private String githubUrl;
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