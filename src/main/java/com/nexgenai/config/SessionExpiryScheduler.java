package com.nexgenai.config;

import com.nexgenai.model.TestSession;
import com.nexgenai.repository.TestSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Expires IN_PROGRESS test sessions that have exceeded their time limit.
 * Runs every 60 seconds. Ensures the timer continues server-side even when
 * the candidate's browser is closed or disconnected.
 */
@Profile("!test")
@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class SessionExpiryScheduler {

    private final TestSessionRepository testSessionRepository;

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void expireTimedOutSessions() {
        List<TestSession> active = testSessionRepository.findByStatus(TestSession.SessionStatus.IN_PROGRESS);
        int expired = 0;
        for (TestSession s : active) {
            if (s.getTimeLimitSeconds() != null && s.getTimeLimitSeconds() > 0 && s.getStartedAt() != null) {
                long elapsed = Duration.between(s.getStartedAt(), LocalDateTime.now()).getSeconds();
                if (elapsed >= s.getTimeLimitSeconds()) {
                    s.setStatus(TestSession.SessionStatus.EXPIRED);
                    s.setCompletedAt(LocalDateTime.now());
                    if (s.getDurationSeconds() == null)
                        s.setDurationSeconds(s.getTimeLimitSeconds());
                    testSessionRepository.save(s);
                    expired++;
                }
            }
        }
        if (expired > 0) log.info("SessionExpiryScheduler: expired {} timed-out sessions", expired);
    }
}
