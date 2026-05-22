package com.nexgenai.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Centralised structured logger for all security events.
 * All entries go to the SECURITY_AUDIT named logger which writes to
 * logs/security-audit.log (see logback-spring.xml).
 *
 * Format:  EVENT_TYPE | ip=... | method=... | url=... | detail=...
 */
@Component
public class SecurityEventLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("SECURITY_AUDIT");

    // ── OWASP A03 — Injection ───────────────────────────────────────────────

    public void xssAttempt(String ip, String method, String url, String location, String payload) {
        AUDIT.warn("XSS_ATTEMPT     | ip={} | method={} | url={} | location={} | payload={}",
                ip, method, url, location, truncate(payload));
    }

    public void sqlInjectionAttempt(String ip, String method, String url, String location, String payload) {
        AUDIT.warn("SQL_INJECTION    | ip={} | method={} | url={} | location={} | payload={}",
                ip, method, url, location, truncate(payload));
    }

    // ── OWASP A01 — Broken Access Control ──────────────────────────────────

    public void unauthorizedAccess(String ip, String method, String url, String user) {
        AUDIT.warn("UNAUTHORIZED     | ip={} | method={} | url={} | user={}",
                ip, method, url, user);
    }

    public void forbiddenAccess(String ip, String method, String url, String user, String role) {
        AUDIT.warn("FORBIDDEN        | ip={} | method={} | url={} | user={} | role={}",
                ip, method, url, user, role);
    }

    public void pathTraversal(String ip, String method, String url) {
        AUDIT.warn("PATH_TRAVERSAL   | ip={} | method={} | url={}", ip, method, url);
    }

    // ── OWASP A04 — Insecure Design / Rate Limiting ─────────────────────────

    public void rateLimitExceeded(String ip, String url, int count) {
        AUDIT.warn("RATE_LIMIT       | ip={} | url={} | count={}", ip, url, count);
    }

    public void bruteForce(String ip, String email) {
        AUDIT.warn("BRUTE_FORCE      | ip={} | email={}", ip, email);
    }

    // ── OWASP A04 / A08 — File Upload ───────────────────────────────────────

    public void maliciousFileUpload(String ip, String filename, String reason) {
        AUDIT.warn("MALICIOUS_FILE   | ip={} | filename={} | reason={}", ip, filename, reason);
    }

    // ── OWASP A07 — Identification & Authentication ─────────────────────────

    public void invalidJwt(String ip, String url, String reason) {
        AUDIT.warn("INVALID_JWT      | ip={} | url={} | reason={}", ip, url, reason);
    }

    public void expiredJwt(String ip, String url) {
        AUDIT.info("EXPIRED_JWT      | ip={} | url={}", ip, url);
    }

    // ── Informational ────────────────────────────────────────────────────────

    public void authSuccess(String ip, String email) {
        AUDIT.info("AUTH_SUCCESS     | ip={} | email={}", ip, email);
    }

    public void authFailure(String ip, String email) {
        AUDIT.warn("AUTH_FAILURE     | ip={} | email={}", ip, email);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String truncate(String s) {
        if (s == null) return "null";
        return s.length() > 200 ? s.substring(0, 200) + "…[truncated]" : s;
    }
}
