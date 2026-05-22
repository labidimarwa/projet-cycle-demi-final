package com.nexgenai.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitingFilter — unit tests")
class RateLimitingFilterTest {

    @Mock  private SecurityEventLogger eventLogger;
    @InjectMocks private RateLimitingFilter filter;

    private MockHttpServletRequest  req;
    private MockHttpServletResponse res;

    @BeforeEach
    void setUp() {
        req = new MockHttpServletRequest();
        res = new MockHttpServletResponse();
        req.setRemoteAddr("192.168.1.1");
    }

    // ── Auth endpoint brute-force limit (10 req/min) ─────────────────────────

    @Test
    @DisplayName("Auth endpoint — first 10 requests → allowed")
    void authEndpoint_within10Requests_allowed() throws Exception {
        req.setRequestURI("/auth/login");

        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse singleRes = new MockHttpServletResponse();
            MockFilterChain         singleChain = new MockFilterChain();
            filter.doFilterInternal(req, singleRes, singleChain);
            assertNotEquals(429, singleRes.getStatus(), "Request " + (i+1) + " should be allowed");
        }
    }

    @Test
    @DisplayName("Auth endpoint — 11th request in same window → HTTP 429")
    void authEndpoint_exceeds10Requests_returns429() throws Exception {
        req.setRequestURI("/auth/login");

        // Send 10 allowed requests
        for (int i = 0; i < 10; i++) {
            filter.doFilterInternal(req, new MockHttpServletResponse(), new MockFilterChain());
        }

        // 11th should be blocked
        filter.doFilterInternal(req, res, new MockFilterChain());

        assertEquals(429, res.getStatus());
        assertEquals("60", res.getHeader("Retry-After"));
        verify(eventLogger, atLeastOnce()).rateLimitExceeded(eq("192.168.1.1"), eq("/auth/login"), anyInt());
        verify(eventLogger, atLeastOnce()).bruteForce(eq("192.168.1.1"), anyString());
    }

    // ── General endpoint limit (200 req/min) ─────────────────────────────────

    @Test
    @DisplayName("General endpoint — 201st request → HTTP 429")
    void generalEndpoint_exceeds200Requests_returns429() throws Exception {
        req.setRequestURI("/api/v1/jobs");

        for (int i = 0; i < 200; i++) {
            filter.doFilterInternal(req, new MockHttpServletResponse(), new MockFilterChain());
        }

        filter.doFilterInternal(req, res, new MockFilterChain());

        assertEquals(429, res.getStatus());
        verify(eventLogger, atLeastOnce()).rateLimitExceeded(anyString(), anyString(), anyInt());
    }

    // ── Different IPs are tracked independently ───────────────────────────────

    @Test
    @DisplayName("Different IPs have independent counters")
    void differentIps_independentCounters() throws Exception {
        MockHttpServletRequest req1 = new MockHttpServletRequest();
        req1.setRequestURI("/auth/login");
        req1.setRemoteAddr("10.0.0.1");

        MockHttpServletRequest req2 = new MockHttpServletRequest();
        req2.setRequestURI("/auth/login");
        req2.setRemoteAddr("10.0.0.2");

        // Exhaust IP1's auth limit
        for (int i = 0; i < 11; i++) {
            filter.doFilterInternal(req1, new MockHttpServletResponse(), new MockFilterChain());
        }

        // IP2 should still be allowed
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilterInternal(req2, res2, new MockFilterChain());

        assertNotEquals(429, res2.getStatus(), "IP2 should not be affected by IP1's limit");
    }

    // ── X-Forwarded-For extraction ────────────────────────────────────────────

    @Test
    @DisplayName("X-Forwarded-For header is used as client IP")
    void xForwardedFor_usedAsClientIp() throws Exception {
        req.setRequestURI("/auth/login");
        req.addHeader("X-Forwarded-For", "203.0.113.42, 10.0.0.1");

        for (int i = 0; i < 11; i++) {
            filter.doFilterInternal(req, new MockHttpServletResponse(), new MockFilterChain());
        }

        verify(eventLogger, atLeastOnce()).rateLimitExceeded(eq("203.0.113.42"), anyString(), anyInt());
    }

    // ── Response body on 429 ─────────────────────────────────────────────────

    @Test
    @DisplayName("429 response has JSON body and Retry-After header")
    void rateLimit429_correctBodyAndHeaders() throws Exception {
        req.setRequestURI("/auth/login");

        for (int i = 0; i < 11; i++) {
            filter.doFilterInternal(req, new MockHttpServletResponse(), new MockFilterChain());
        }

        filter.doFilterInternal(req, res, new MockFilterChain());

        assertEquals(429, res.getStatus());
        assertEquals("application/json", res.getContentType());
        assertTrue(res.getContentAsString().contains("Too Many Requests"));
        assertNotNull(res.getHeader("Retry-After"));
    }
}
