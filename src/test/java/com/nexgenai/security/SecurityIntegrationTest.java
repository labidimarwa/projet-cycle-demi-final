package com.nexgenai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexgenai.service.CodeExecutionService;
import com.nexgenai.service.EmailService;
import com.nexgenai.security.SecurityAuditFilter;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration sécurité — SECURITY-TESTS.md
 *
 * Couvre automatiquement :
 *   Sect. 1 — XSS         : filter query params + @Pattern DTO validation
 *   Sect. 2 — SQL Injection: SecurityAuditFilter bloque les patterns SQLi → 400
 *   Sect. 3 — RBAC        : accès inter-rôles (403/401)
 *   Sect. 4 — File Upload : magic bytes, extension blocklist, path traversal
 *   Sect. 5 — Headers     : OWASP Secure Headers sur toute réponse API
 *
 * Tests manuels restants (Postman / ZAP) :
 *   Test 1.1 — XSS job description dans le navigateur (Angular)
 *   Test 1.3 / 2.3 / 3.5 / 4.6 — scans OWASP ZAP
 *   Test 3.4 — HR accède au job d'un autre HR (nécessite deux comptes HR réels)
 *   Test 4.3 — Upload > 10 MB (limite multipart Spring Boot)
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Security Integration Tests — XSS · SQLi · RBAC · File Upload · Headers")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityIntegrationTest {

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper          objectMapper;
    @Autowired private SecurityAuditFilter   securityAuditFilter;
    private           MockMvc                mockMvc;

    @MockBean private EmailService         emailService;
    @MockBean private CodeExecutionService codeExecutionService;

    private static final String TEST_IP   = "10.70.0.1";   // IP isolée pour le rate-limiter
    private static final String AUTH_BASE = "/auth";
    private static final String CAND_BASE = "/candidate";
    private static final String HR_BASE   = "/hr";
    private static final String JOBS_BASE = "/jobs";

    private static final String SEC_EMAIL = "security.test@nexgenai.com";
    private static final String SEC_PWD   = "SecTest123!";

    private String candidateToken;

    // ── Setup global ──────────────────────────────────────────────────────────

    @BeforeAll
    void globalSetup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .addFilter(securityAuditFilter, "/*")   // force l'application du filtre d'audit
            .defaultRequest(MockMvcRequestBuilders.get("/").header("X-Forwarded-For", TEST_IP))
            .build();

        Map<String, String> reg = Map.of(
            "firstName", "Security", "lastName", "Tester",
            "email", SEC_EMAIL,
            "password", SEC_PWD, "confirmPassword", SEC_PWD
        );
        MvcResult r = mockMvc.perform(post(AUTH_BASE + "/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reg)))
                .andReturn();

        candidateToken = objectMapper.readTree(r.getResponse().getContentAsString())
                .get("token").asText();
        assertFalse(candidateToken == null || candidateToken.isBlank(),
            "Le token du candidat de test ne doit pas être vide");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. XSS — Cross-Site Scripting
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-SEC-01 (XSS 1.2) : PUT /candidate/profile firstName=<img onerror=alert(1)> → 400")
    void xss_maliciousImgTagInFirstName_returns400() throws Exception {
        Map<String, Object> req = Map.of(
            "firstName", "<img src=x onerror=alert(1)>",
            "lastName",  "Normal"
        );
        mockMvc.perform(put(CAND_BASE + "/profile")
                .header("Authorization", "Bearer " + candidateToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
               .andExpect(status().isBadRequest());
    }

    @Test
    @Order(2)
    @DisplayName("TC-SEC-02 (XSS 1.2) : PUT /candidate/profile city=\"'; DROP TABLE users; --\" → 400")
    void xss_sqlPayloadInCityField_returns400() throws Exception {
        Map<String, Object> req = Map.of("city", "'; DROP TABLE users; --");
        mockMvc.perform(put(CAND_BASE + "/profile")
                .header("Authorization", "Bearer " + candidateToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
               .andExpect(status().isBadRequest());
    }

    @Test
    @Order(3)
    @DisplayName("TC-SEC-03 (XSS filter) : GET /jobs?title=<script>alert(1)</script> → 400 (SecurityAuditFilter)")
    void xss_scriptTagInQueryParam_returns400() throws Exception {
        mockMvc.perform(get(JOBS_BASE)
                .param("title", "<script>alert('xss')</script>"))
               .andExpect(status().isBadRequest());
    }

    @Test
    @Order(4)
    @DisplayName("TC-SEC-04 (XSS filter) : GET /jobs?title=javascript:alert(1) → 400 (SecurityAuditFilter)")
    void xss_javascriptProtocolInQueryParam_returns400() throws Exception {
        mockMvc.perform(get(JOBS_BASE)
                .param("search", "javascript:alert(1)"))
               .andExpect(status().isBadRequest());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. SQL Injection
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("TC-SEC-05 (SQLi 2.1) : GET /jobs?title=' OR '1'='1 → 400 (SecurityAuditFilter)")
    void sqli_classicOrPayload_returns400() throws Exception {
        mockMvc.perform(get(JOBS_BASE)
                .param("title", "' OR '1'='1"))
               .andExpect(status().isBadRequest());
    }

    @Test
    @Order(6)
    @DisplayName("TC-SEC-06 (SQLi 2.2) : GET /jobs?title=test' UNION SELECT username,password FROM users-- → 400")
    void sqli_unionSelectPayload_returns400() throws Exception {
        mockMvc.perform(get(JOBS_BASE)
                .param("title", "test' UNION SELECT username,password FROM users--"))
               .andExpect(status().isBadRequest());
    }

    @Test
    @Order(7)
    @DisplayName("TC-SEC-07 (SQLi) : GET /jobs?title=; DROP TABLE jobs; -- → 400")
    void sqli_dropTablePayload_returns400() throws Exception {
        mockMvc.perform(get(JOBS_BASE)
                .param("title", "; DROP TABLE jobs; --"))
               .andExpect(status().isBadRequest());
    }

    @Test
    @Order(8)
    @DisplayName("TC-SEC-08 (SQLi) : GET /jobs?title=normal (valide) → 200 pas de faux positif")
    void sqli_normalTitle_noFalsePositive_returns200() throws Exception {
        mockMvc.perform(get(JOBS_BASE)
                .param("title", "Développeur Java Senior"))
               .andExpect(status().isOk());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. RBAC — Broken Access Control
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(9)
    @DisplayName("TC-SEC-09 (RBAC 3.1) : CANDIDATE → GET /hr/jobs/{id}/applicants → 403 Forbidden")
    void rbac_candidateAccessesHrApplicants_returns403() throws Exception {
        mockMvc.perform(get(HR_BASE + "/jobs/some-job-id/applicants")
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @Order(10)
    @DisplayName("TC-SEC-10 (RBAC 3.1) : CANDIDATE → GET /hr/applicants/{id}/cv → 403 Forbidden")
    void rbac_candidateAccessesHrCvDownload_returns403() throws Exception {
        mockMvc.perform(get(HR_BASE + "/applicants/some-candidate-id/cv")
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isForbidden());
    }

    @Test
    @Order(11)
    @DisplayName("TC-SEC-11 (RBAC 3.2) : No auth → GET /candidate/profile → 401 Unauthorized")
    void rbac_noAuthOnCandidateProfile_returns401() throws Exception {
        mockMvc.perform(get(CAND_BASE + "/profile"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(12)
    @DisplayName("TC-SEC-12 (RBAC 3.2) : No auth → GET /hr/applicants/{id}/cv → 401 Unauthorized")
    void rbac_noAuthOnHrCvEndpoint_returns401() throws Exception {
        mockMvc.perform(get(HR_BASE + "/applicants/some-id/cv"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(13)
    @DisplayName("TC-SEC-13 (RBAC 3.2) : No auth → POST /candidate/apply → 401 Unauthorized")
    void rbac_noAuthOnApplyEndpoint_returns401() throws Exception {
        mockMvc.perform(multipart(CAND_BASE + "/apply")
                .param("jobId", "some-job-id"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(14)
    @DisplayName("TC-SEC-14 (RBAC) : HR → GET /admin/users → 403 (admin endpoint)")
    @WithMockUser(roles = "HR")
    void rbac_hrAccessesAdminEndpoint_returns403() throws Exception {
        mockMvc.perform(get("/users"))
               .andExpect(status().isForbidden());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. Malicious File Upload
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(15)
    @DisplayName("TC-SEC-15 (Upload 4.1) : PDF avec magic bytes MZ (exécutable Windows) → 400")
    void upload_executableRenamedAsPdf_returns400() throws Exception {
        byte[] mzBytes = new byte[64];
        mzBytes[0] = 0x4D; mzBytes[1] = 0x5A;   // MZ — Windows PE header
        MockMultipartFile f = new MockMultipartFile(
            "file", "malware.pdf", "application/pdf", mzBytes
        );
        mockMvc.perform(multipart(CAND_BASE + "/cv")
                .file(f)
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isBadRequest());
    }

    @Test
    @Order(16)
    @DisplayName("TC-SEC-16 (Upload 4.2) : Fichier .sh (shell script) → 400 (extension bloquée)")
    void upload_shellScriptExtension_returns400() throws Exception {
        byte[] content = "#!/bin/bash\nrm -rf /\n".getBytes();
        MockMultipartFile f = new MockMultipartFile(
            "file", "exploit.sh", "text/plain", content
        );
        mockMvc.perform(multipart(CAND_BASE + "/cv")
                .file(f)
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isBadRequest());
    }

    @Test
    @Order(17)
    @DisplayName("TC-SEC-17 (Upload 4.2) : Fichier .exe → 400 (extension bloquée)")
    void upload_executableExtension_returns400() throws Exception {
        byte[] content = {0x4D, 0x5A, 0x00, 0x00};
        MockMultipartFile f = new MockMultipartFile(
            "file", "virus.exe", "application/octet-stream", content
        );
        mockMvc.perform(multipart(CAND_BASE + "/cv")
                .file(f)
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isBadRequest());
    }

    @Test
    @Order(18)
    @DisplayName("TC-SEC-18 (Upload 4.4) : Filename ../../etc/passwd.pdf → 400 (path traversal)")
    void upload_pathTraversalInFilename_returns400() throws Exception {
        byte[] pdfBytes = {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A,
                           0x25, (byte)0xE2, (byte)0xE3, (byte)0xCF, (byte)0xD3, 0x0A};
        MockMultipartFile f = new MockMultipartFile(
            "file", "../../etc/passwd.pdf", "application/pdf", pdfBytes
        );
        mockMvc.perform(multipart(CAND_BASE + "/cv")
                .file(f)
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isBadRequest());
    }

    @Test
    @Order(19)
    @DisplayName("TC-SEC-19 (Upload 4.5) : PDF valide avec magic bytes corrects → 200 (régression)")
    void upload_validPdfWithCorrectMagicBytes_returns200() throws Exception {
        byte[] pdfBytes = {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A,
                           0x25, (byte)0xE2, (byte)0xE3, (byte)0xCF, (byte)0xD3, 0x0A};
        MockMultipartFile f = new MockMultipartFile(
            "file", "mon_cv.pdf", "application/pdf", pdfBytes
        );
        mockMvc.perform(multipart(CAND_BASE + "/cv")
                .file(f)
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isOk());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. Security Response Headers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    @DisplayName("TC-SEC-20 (Headers) : Tous les headers OWASP Secure Headers présents sur GET /jobs")
    void securityHeaders_allOwaspHeadersPresent() throws Exception {
        MvcResult result = mockMvc.perform(get(JOBS_BASE))
               .andExpect(status().isOk())
               .andReturn();

        var resp = result.getResponse();
        assertAll("OWASP Secure Headers",
            () -> assertEquals("nosniff",
                    resp.getHeader("X-Content-Type-Options"),
                    "X-Content-Type-Options doit être 'nosniff'"),
            () -> assertEquals("DENY",
                    resp.getHeader("X-Frame-Options"),
                    "X-Frame-Options doit être 'DENY'"),
            // Spring Security 6 désactive volontairement X-XSS-Protection (header navigateur déprécié)
            () -> assertNotNull(
                    resp.getHeader("X-XSS-Protection"),
                    "X-XSS-Protection doit être présent (valeur 0 acceptée en SS6)"),
            () -> assertNotNull(
                    resp.getHeader("Referrer-Policy"),
                    "Referrer-Policy doit être présent"),
            // Spring Security enrichit Cache-Control ; la valeur exacte inclut no-store
            () -> assertNotNull(
                    resp.getHeader("Cache-Control"),
                    "Cache-Control doit être présent"),
            () -> assertTrue(
                    resp.getHeader("Cache-Control") != null &&
                    resp.getHeader("Cache-Control").contains("no-store"),
                    "Cache-Control doit contenir 'no-store'"),
            () -> assertNotNull(
                    resp.getHeader("Content-Security-Policy"),
                    "Content-Security-Policy doit être présent"),
            () -> assertNotNull(
                    resp.getHeader("Permissions-Policy"),
                    "Permissions-Policy doit être présent")
        );
    }
}
