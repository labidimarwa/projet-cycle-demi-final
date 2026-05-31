package com.nexgenai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexgenai.model.enums.ContractType;
import com.nexgenai.model.enums.ExperienceLevel;
import com.nexgenai.service.CodeExecutionService;
import com.nexgenai.service.EmailService;

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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration du CandidateController — Sprint 1.
 *
 * Couvre les User Stories :
 *   - US-14 : Consulter les informations de profil (GET /candidate/profile)
 *   - US-15 : Modifier les informations de profil (PUT /candidate/profile)
 *   - US-20 : Postuler à une annonce en important un CV (POST /candidate/apply)
 *
 * Stratégie :
 *   @TestInstance(PER_CLASS) : une seule instance de classe pour tous les tests
 *   → permet de partager candidateToken et testJobId entre les méthodes via @BeforeAll.
 *
 * Base de données : H2 en mémoire (profil "test").
 * Services externes mockés : EmailService, CodeExecutionService.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("CandidateController — Tests d'Intégration Sprint 1")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CandidateControllerTest {

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper          objectMapper;
    private           MockMvc                mockMvc;

    // IP unique pour isoler le compteur du RateLimitingFilter
    private static final String TEST_IP    = "10.50.0.1";

    // Services externes simulés pour éviter des appels réels
    @MockBean private EmailService         emailService;
    @MockBean private CodeExecutionService codeExecutionService;

    private static final String AUTH_BASE = "/auth";
    private static final String CAND_BASE = "/candidate";
    private static final String JOBS_BASE = "/jobs";

    // Identifiants du candidat de test (uniques pour ne pas collisionner avec d'autres classes)
    private static final String CANDIDATE_EMAIL = "sprint1.cand@test.com";
    private static final String CANDIDATE_PWD   = "Sprint1Pass!";

    // Partagés entre les méthodes de test grâce à PER_CLASS
    private String candidateToken;
    private String testJobId;

    // ── Setup global (une seule fois avant tous les tests de la classe) ────────

    @BeforeAll
    void globalSetup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .defaultRequest(MockMvcRequestBuilders.get("/")
                .header("X-Forwarded-For", TEST_IP))
            .build();

        // Inscrire le candidat de test et récupérer son token JWT
        Map<String, String> req = Map.of(
            "firstName", "Sprint1", "lastName", "Cand",
            "email", CANDIDATE_EMAIL,
            "password", CANDIDATE_PWD,
            "confirmPassword", CANDIDATE_PWD
        );
        MvcResult result = mockMvc.perform(post(AUTH_BASE + "/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andReturn();

        candidateToken = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asText();
        assertFalse(candidateToken == null || candidateToken.isBlank(),
            "Le token du candidat de test ne doit pas être vide");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-14 : Consulter le profil candidat — GET /candidate/profile
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-CCTRL-01 (US-14) : GET /candidate/profile sans authentification → 401 Unauthorized")
    void getProfile_noAuth_returns401() throws Exception {
        mockMvc.perform(get(CAND_BASE + "/profile"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    @DisplayName("TC-CCTRL-02 (US-14) : GET /candidate/profile avec token JWT candidat valide → 200 + email et prénom corrects")
    void getProfile_withValidCandidateToken_returns200WithProfileData() throws Exception {
        MvcResult result = mockMvc.perform(get(CAND_BASE + "/profile")
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isOk())
               .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
               .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains(CANDIDATE_EMAIL), "Le profil doit contenir l'email du candidat");
        assertTrue(body.contains("Sprint1"), "Le profil doit contenir le prénom enregistré");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-15 : Modifier le profil candidat — PUT /candidate/profile
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("TC-CCTRL-03 (US-15) : PUT /candidate/profile données valides → 200 + profil mis à jour")
    void updateProfile_validRequest_returns200WithUpdatedData() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("firstName", "Sprint1Updated");
        req.put("lastName",  "CandMod");
        req.put("city",      "Tunis");

        MvcResult result = mockMvc.perform(put(CAND_BASE + "/profile")
                .header("Authorization", "Bearer " + candidateToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
               .andExpect(status().isOk())
               .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("Sprint1Updated"), "Le prénom doit être mis à jour dans la réponse");
    }

    @Test
    @Order(4)
    @DisplayName("TC-CCTRL-04 (US-15) : PUT /candidate/profile sans authentification → 401")
    void updateProfile_noAuth_returns401() throws Exception {
        Map<String, Object> req = Map.of("firstName", "Hacker");

        mockMvc.perform(put(CAND_BASE + "/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    @DisplayName("TC-CCTRL-05 (US-15) : PUT /candidate/profile avec injection HTML dans firstName → 400 (validation @Pattern)")
    void updateProfile_htmlInjectionInFirstName_returns400() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("firstName", "<script>alert('xss')</script>");
        req.put("lastName",  "Normal");

        // Le SecurityAuditFilter ou la validation @Pattern bloque les caractères HTML
        mockMvc.perform(put(CAND_BASE + "/profile")
                .header("Authorization", "Bearer " + candidateToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
               .andExpect(status().isBadRequest());
    }

    @Test
    @Order(6)
    @DisplayName("TC-CCTRL-06 (US-15) : PUT /candidate/profile avec URL LinkedIn de type javascript: → 400 (validation @Pattern)")
    void updateProfile_javascriptProtocolInLinkedinUrl_returns400() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("linkedinUrl", "javascript:alert(1)");

        mockMvc.perform(put(CAND_BASE + "/profile")
                .header("Authorization", "Bearer " + candidateToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
               .andExpect(status().isBadRequest());
    }

    @Test
    @Order(7)
    @DisplayName("TC-CCTRL-07 (US-15) : PUT /candidate/profile URL HTTPS valide → 200 (URL acceptée)")
    void updateProfile_validHttpsUrl_returns200() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("linkedinUrl",  "https://linkedin.com/in/sprint1cand");
        req.put("githubUrl",    "https://github.com/sprint1cand");
        req.put("portfolioUrl", "https://sprint1cand.dev");

        mockMvc.perform(put(CAND_BASE + "/profile")
                .header("Authorization", "Bearer " + candidateToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
               .andExpect(status().isOk());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-20 : Postuler à une annonce — POST /candidate/apply
    //
    // Prérequis : un job doit exister en base.
    // TC-CCTRL-08 crée ce job en tant que RH (@WithMockUser) et stocke l'ID.
    // Les tests suivants utilisent cet ID avec le token candidat.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(8)
    @DisplayName("TC-CCTRL-08 [Setup US-20] : POST /jobs en tant que HR → annonce créée, ID mémorisé")
    @WithMockUser(roles = "HR")
    void setup_createJobForApplicationTests() throws Exception {
        Map<String, Object> jobReq = new HashMap<>();
        jobReq.put("title",           "Poste Sprint1 Test");
        jobReq.put("department",      "IT");
        jobReq.put("location",        "Tunis");
        jobReq.put("contractType",    ContractType.CONTRACT.name());
        jobReq.put("experienceLevel", ExperienceLevel.MID_LEVEL.name());
        jobReq.put("description",     "Annonce créée pour les tests de candidature Sprint 1");
        jobReq.put("openPositions",   2);
        jobReq.put("isRemote",        false);

        MvcResult result = mockMvc.perform(post(JOBS_BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(jobReq)))
               .andExpect(status().isCreated())
               .andReturn();

        testJobId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
        assertFalse(testJobId == null || testJobId.isBlank(),
            "L'ID du job créé ne doit pas être vide");
    }

    @Test
    @Order(9)
    @DisplayName("TC-CCTRL-09 (US-20) : POST /candidate/apply sans authentification → 401")
    void apply_noAuth_returns401() throws Exception {
        mockMvc.perform(multipart(CAND_BASE + "/apply")
                .param("jobId", "some-job-id"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(10)
    @DisplayName("TC-CCTRL-10 (US-20) : POST /candidate/apply avec token + CV PDF valide → 200 + confirmation")
    void apply_withValidCandidateTokenAndPdf_returns200WithConfirmation() throws Exception {
        // Fichier PDF minimal : magic bytes %PDF-1.4 + contenu fictif
        byte[] pdfContent = {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A,
                             0x25, (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, 0x0A};
        MockMultipartFile cv = new MockMultipartFile(
            "cv", "mon_cv.pdf", "application/pdf", pdfContent
        );

        MvcResult result = mockMvc.perform(multipart(CAND_BASE + "/apply")
                .file(cv)
                .param("jobId", testJobId)
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isOk())
               .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("submitted") || body.contains("Application"),
            "La réponse doit confirmer la soumission de la candidature");
        assertTrue(body.contains(testJobId),
            "La réponse doit contenir l'ID du job postulé");
    }

    @Test
    @Order(11)
    @DisplayName("TC-CCTRL-11 (US-20) : POST /candidate/apply même job deux fois → 200 idempotent (pas de doublon)")
    void apply_duplicateApplicationSameJob_returns200Idempotent() throws Exception {
        // Deuxième postulation au même job → doit être ignorée silencieusement
        byte[] pdfContent = {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A};
        MockMultipartFile cv = new MockMultipartFile(
            "cv", "mon_cv.pdf", "application/pdf", pdfContent
        );

        mockMvc.perform(multipart(CAND_BASE + "/apply")
                .file(cv)
                .param("jobId", testJobId)
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isOk());
        // La candidature en doublon est silencieusement ignorée par ApplicationService
    }

    @Test
    @Order(12)
    @DisplayName("TC-CCTRL-12 (US-20) : GET /candidate/applications après candidature → 200 + liste non vide")
    void getApplications_afterApply_returns200WithNonEmptyList() throws Exception {
        MvcResult result = mockMvc.perform(get(CAND_BASE + "/applications")
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isOk())
               .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
               .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.startsWith("["), "La réponse doit être un tableau JSON");
        assertNotEquals("[]", body,
            "Le candidat doit avoir au moins une candidature après TC-CCTRL-10");
    }

    @Test
    @Order(13)
    @DisplayName("TC-CCTRL-13 (US-16) : GET /candidate/matches → 200 + map de scores (éventuellement vide)")
    void getMatchScores_returns200WithJsonMap() throws Exception {
        mockMvc.perform(get(CAND_BASE + "/matches")
                .header("Authorization", "Bearer " + candidateToken))
               .andExpect(status().isOk())
               .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
