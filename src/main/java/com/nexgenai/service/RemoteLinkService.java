package com.nexgenai.service;

import com.nexgenai.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.UUID;

/**
 * Generates a shareable remote application link.
 * Format: {frontendBaseUrl}/apply/remote/{jobId}?token={token}
 *
 * The token is a short Base64-encoded UUID to make links look professional.
 * For production, you could persist tokens in DB for validation.
 */
@Service
public class RemoteLinkService {

    @Value("${app.frontend-url:http://localhost:4200}")
    private String frontendBaseUrl;

    private final JobRepository jobRepository;

    public RemoteLinkService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    public String generateLink(String jobId) {
        // Verify job exists
        jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

        // Generate a short token
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(UUID.randomUUID().toString().getBytes())
                .substring(0, 16);

        return String.format("%s/apply/remote/%s?token=%s", frontendBaseUrl, jobId, token);
    }
}