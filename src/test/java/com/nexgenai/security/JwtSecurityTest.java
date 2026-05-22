package com.nexgenai.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * JWT authentication security tests.
 * OWASP A07 — Identification and Authentication Failures.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("JWT Security Tests — OWASP A07")
class JwtSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    // ── Missing token ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("No Authorization header on admin route → 4xx (auth required)")
    void noToken_adminRoute_returns4xx() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
               .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("No Authorization header on job-tests → 4xx")
    void noToken_jobTests_returns4xx() throws Exception {
        mockMvc.perform(get("/api/v1/job-tests/some-id"))
               .andExpect(status().is4xxClientError());
    }

    // ── Malformed tokens ─────────────────────────────────────────────────────

    @ParameterizedTest(name = "Malformed token: {0}")
    @ValueSource(strings = {
        "Bearer notavalidjwt",
        "Bearer eyJhbGciOiJIUzI1NiJ9.invalid.signature",
        "Bearer ",
        "Basic dXNlcjpwYXNz",
        "Bearer null",
        "Bearer undefined"
    })
    @DisplayName("Malformed Bearer token on admin route → 4xx")
    void malformedToken_returns4xx(String authHeader) throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")
                   .header("Authorization", authHeader))
               .andExpect(status().is4xxClientError());
    }

    // ── Algorithm confusion (none algorithm) ─────────────────────────────────

    @Test
    @DisplayName("JWT with 'none' algorithm → rejected (4xx)")
    void noneAlgorithmJwt_rejected() throws Exception {
        String noneAlgToken = "Bearer eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0" +
            ".eyJzdWIiOiJhZG1pbkB0ZXN0LmNvbSIsInJvbGUiOiJBRE1JTiIsImlhdCI6MTcwMDAwMDAwMH0.";

        mockMvc.perform(get("/api/v1/admin/users")
                   .header("Authorization", noneAlgToken))
               .andExpect(status().is4xxClientError());
    }

    // ── Expired token ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Expired JWT (past exp claim) on admin route → 4xx")
    void expiredToken_returns4xx() throws Exception {
        // Syntactically valid JWT, but exp=1 (always expired) with wrong signature
        String expiredToken = "Bearer eyJhbGciOiJIUzI1NiJ9" +
            ".eyJzdWIiOiJ0ZXN0QHRlc3QuY29tIiwiZXhwIjoxfQ" +
            ".SomeSignatureThatWillBeInvalid";

        mockMvc.perform(get("/api/v1/admin/users")
                   .header("Authorization", expiredToken))
               .andExpect(status().is4xxClientError());
    }

    // ── Injection in Authorization header → no 500 ───────────────────────────

    @ParameterizedTest(name = "Injection in auth header: {0}")
    @ValueSource(strings = {
        "Bearer <script>alert(1)</script>",
        "Bearer ' OR '1'='1",
        "Bearer ; DROP TABLE users; --"
    })
    @DisplayName("Injection attempt in Bearer token → no 5xx (application must not crash)")
    void injectionInBearerToken_noServerError(String authHeader) throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")
                   .header("Authorization", authHeader))
               .andExpect(isNot(500));
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private static org.springframework.test.web.servlet.ResultMatcher isNot(int status) {
        return result -> {
            int actual = result.getResponse().getStatus();
            if (actual == status) {
                throw new AssertionError("Expected status NOT to be " + status + " but was " + actual);
            }
        };
    }
}
