// InterviewBootstrapRunner.java
package com.nexgenai.config;

import com.nexgenai.service.InterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs once at application startup.
 * Creates interviews for any existing jobs that don't have them yet.
 * Safe to run multiple times — the service skips already-created interviews.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InterviewBootstrapRunner implements ApplicationRunner {

    private final InterviewService interviewService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("▶ InterviewBootstrapRunner: checking for missing interviews...");
        try {
            interviewService.bootstrapInterviewsForAllJobs();
            log.info("✅ InterviewBootstrapRunner: done.");
        } catch (Exception e) {
            log.error("❌ InterviewBootstrapRunner failed: {}", e.getMessage(), e);
        }
    }
}