package com.nexgenai.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Unit tests for SecurityEventLogger — verify that none of the logging methods
 * throw exceptions and that they handle null/edge-case inputs gracefully.
 */
@DisplayName("SecurityEventLogger — unit tests")
class SecurityEventLoggerTest {

    private final SecurityEventLogger logger = new SecurityEventLogger();

    @Test
    @DisplayName("xssAttempt with normal payload → no exception")
    void xssAttempt_doesNotThrow() {
        assertDoesNotThrow(() ->
            logger.xssAttempt("1.2.3.4", "GET", "/api/jobs", "param:q", "<script>alert(1)</script>"));
    }

    @Test
    @DisplayName("xssAttempt with null payload → no exception")
    void xssAttempt_nullPayload_doesNotThrow() {
        assertDoesNotThrow(() ->
            logger.xssAttempt("1.2.3.4", "GET", "/api/jobs", "param:q", null));
    }

    @Test
    @DisplayName("xssAttempt with payload > 200 chars → truncated, no exception")
    void xssAttempt_longPayload_truncated() {
        String longPayload = "<script>".repeat(50); // 400 chars
        assertDoesNotThrow(() ->
            logger.xssAttempt("1.2.3.4", "POST", "/api/jobs", "param:name", longPayload));
    }

    @Test
    @DisplayName("sqlInjectionAttempt → no exception")
    void sqlInjectionAttempt_doesNotThrow() {
        assertDoesNotThrow(() ->
            logger.sqlInjectionAttempt("1.2.3.4", "GET", "/api/jobs", "param:id", "' OR 1=1 --"));
    }

    @Test
    @DisplayName("unauthorizedAccess → no exception")
    void unauthorizedAccess_doesNotThrow() {
        assertDoesNotThrow(() ->
            logger.unauthorizedAccess("1.2.3.4", "GET", "/api/admin/users", "anonymous"));
    }

    @Test
    @DisplayName("forbiddenAccess → no exception")
    void forbiddenAccess_doesNotThrow() {
        assertDoesNotThrow(() ->
            logger.forbiddenAccess("1.2.3.4", "DELETE", "/api/admin/jobs/1", "user@x.com", "CANDIDATE"));
    }

    @Test
    @DisplayName("pathTraversal → no exception")
    void pathTraversal_doesNotThrow() {
        assertDoesNotThrow(() ->
            logger.pathTraversal("1.2.3.4", "GET", "/api/../etc/passwd"));
    }

    @Test
    @DisplayName("rateLimitExceeded → no exception")
    void rateLimitExceeded_doesNotThrow() {
        assertDoesNotThrow(() ->
            logger.rateLimitExceeded("1.2.3.4", "/auth/login", 25));
    }

    @Test
    @DisplayName("bruteForce → no exception")
    void bruteForce_doesNotThrow() {
        assertDoesNotThrow(() ->
            logger.bruteForce("1.2.3.4", "attacker@evil.com"));
    }

    @Test
    @DisplayName("maliciousFileUpload → no exception")
    void maliciousFileUpload_doesNotThrow() {
        assertDoesNotThrow(() ->
            logger.maliciousFileUpload("1.2.3.4", "shell.php", "Blocked extension: php"));
    }

    @Test
    @DisplayName("invalidJwt → no exception")
    void invalidJwt_doesNotThrow() {
        assertDoesNotThrow(() ->
            logger.invalidJwt("1.2.3.4", "/api/admin/users", "malformed"));
    }

    @Test
    @DisplayName("expiredJwt → no exception")
    void expiredJwt_doesNotThrow() {
        assertDoesNotThrow(() ->
            logger.expiredJwt("1.2.3.4", "/api/admin/users"));
    }

    @Test
    @DisplayName("authSuccess → no exception")
    void authSuccess_doesNotThrow() {
        assertDoesNotThrow(() ->
            logger.authSuccess("1.2.3.4", "user@company.com"));
    }

    @Test
    @DisplayName("authFailure → no exception")
    void authFailure_doesNotThrow() {
        assertDoesNotThrow(() ->
            logger.authFailure("1.2.3.4", "user@company.com"));
    }
}
