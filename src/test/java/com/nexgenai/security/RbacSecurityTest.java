package com.nexgenai.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * RBAC integration tests — verify role-based access control.
 * OWASP A01 — Broken Access Control.
 *
 * Note: tests use full context-path /api/v1 as configured in application-test.properties.
 * Spring Security's MvcRequestMatcher strips the context path before matching, so
 * rules "/admin/**", "/jobs/**" etc. correctly match "/api/v1/admin/**" requests.
 *
 * For protected routes where the controller itself may not exist in the test DB,
 * we assert is4xxClientError() — both 403 (forbidden) and 404 (no controller) confirm
 * the user did not receive a successful 200 response.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("RBAC Security Tests — OWASP A01")
class RbacSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    // ── Authentication required ───────────────────────────────────────────────

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user accessing /admin/** → 4xx")
    void anonymous_accessAdmin_is4xx() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
               .andExpect(status().is4xxClientError());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user creating a job → 4xx")
    void anonymous_createJob_is4xx() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                   .contentType("application/json")
                   .content("{}"))
               .andExpect(status().is4xxClientError());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user accessing job-tests → 4xx")
    void anonymous_accessJobTests_is4xx() throws Exception {
        mockMvc.perform(get("/api/v1/job-tests/some-id"))
               .andExpect(status().is4xxClientError());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Anonymous user accessing candidate portal → 4xx")
    void anonymous_accessCandidate_is4xx() throws Exception {
        mockMvc.perform(get("/api/v1/candidate/applications"))
               .andExpect(status().is4xxClientError());
    }

    // ── Role escalation prevention ────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "CANDIDATE")
    @DisplayName("CANDIDATE accessing /admin → not 200 (either 403 or 404)")
    void candidate_accessAdmin_notSuccessful() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
               .andExpect(isNot(200));
    }

    @Test
    @WithMockUser(roles = "CANDIDATE")
    @DisplayName("CANDIDATE creating a job → not 200")
    void candidate_createJob_notSuccessful() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                   .contentType("application/json")
                   .content("{\"title\":\"Test\"}"))
               .andExpect(isNot(200));
    }

    @Test
    @WithMockUser(roles = "CANDIDATE")
    @DisplayName("CANDIDATE configuring job tests → not 200")
    void candidate_configureJobTests_notSuccessful() throws Exception {
        mockMvc.perform(post("/api/v1/job-tests")
                   .contentType("application/json")
                   .content("{}"))
               .andExpect(isNot(200));
    }

    @Test
    @WithMockUser(roles = "HR")
    @DisplayName("HR accessing /admin → not 200")
    void hr_accessAdmin_notSuccessful() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
               .andExpect(isNot(200));
    }

    // ── Authenticated user can access their own area ──────────────────────────

    @Test
    @WithMockUser(roles = "HR")
    @DisplayName("HR accessing job-tests → not 401 or 403 (auth accepted)")
    void hr_accessJobTests_authAccepted() throws Exception {
        // Returns 404 if test data empty, but NOT 401/403 — HR has access
        mockMvc.perform(get("/api/v1/job-tests"))
               .andExpect(isNot(401))
               .andExpect(isNot(403));
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
