package com.nexgenai.dto.candidate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateProfileResponse {
    private String id;
    private String firstName;
    private String lastName;
    private String email;
    private String city;
    private String currentPosition;
    private Integer yearsOfExperience;
    private String educationLevel;
    private String university;
    private String summary;
    private String cvPath;
    private String linkedinUrl;
    private String githubUrl;
    private String portfolioUrl;
    private String remoteWorkPreference;
    private String certifications;
    private List<LinkDTO> links;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkDTO {
        private String title;
        private String url;
    }
}