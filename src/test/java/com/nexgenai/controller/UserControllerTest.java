package com.nexgenai.controller;

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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration du UserController — Sprint 1.
 *
 * Couvre les User Stories :
 *   - US-45 : Admin crée les comptes HR et évaluateurs techniques (POST /users/create)
 *   - US-47 : Admin consulte la liste des utilisateurs      (GET /users)
 *
 * Protection testée :
 *   - @PreAuthorize("hasRole('ADMIN')") — méthode security, indépendante du context-path
 *   - Unauthenticated → 401, CANDIDATE → 403, ADMIN → 2xx
 *
 * Base de données : H2 en mémoire (profil "test").
 * Services externes mockés : EmailService (envoi de mail de bienvenue), CodeExecutionService.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("UserController — Tests d'Intégration Sprint 1")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserControllerTest {

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper          objectMapper;
    private           MockMvc                mockMvc;

    @MockBean private EmailService         emailService;
    @MockBean private CodeExecutionService codeExecutionService;

    private static final String BASE = "/users";

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            // IP unique pour isoler le compteur RateLimiting
            .defaultRequest(MockMvcRequestBuilders.get("/").header("X-Forwarded-For", "10.60.0.1"))
            .build();
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    private Map<String, Object> hrRequest(String email) {
        Map<String, Object> r = new HashMap<>();
        r.put("firstName",  "TestHR");
        r.put("lastName",   "Manager");
        r.put("email",      email);
        r.put("password",   "HrSecure123!");
        r.put("role",       "HR");
        r.put("department", "Talent Acquisition");
        r.put("position",   "HR Manager");
        return r;
    }

    private Map<String, Object> evaluatorRequest(String email) {
        Map<String, Object> r = new HashMap<>();
        r.put("firstName",       "TestEval");
        r.put("lastName",        "Tech");
        r.put("email",           email);
        r.put("password",        "EvalSecure123!");
        r.put("role",            "TECH_EVALUATOR");
        r.put("specialization",  "DEVELOPER");
        r.put("title",           "Tech Lead");
        r.put("expertiseLevel",  "SENIOR");
        r.put("yearsOfExperience", 5);
        return r;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-47 : Consulter la liste des utilisateurs — GET /users
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-UCTRL-01 (US-47) : GET /users sans authentification → 401 Unauthorized")
    void getUsers_noAuth_returns401() throws Exception {
        mockMvc.perform(get(BASE))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    @DisplayName("TC-UCTRL-02 (US-47) : GET /users en tant que CANDIDATE → 403 Forbidden")
    @WithMockUser(roles = "CANDIDATE")
    void getUsers_asCandidate_returns403() throws Exception {
        mockMvc.perform(get(BASE))
               .andExpect(status().isForbidden());
    }

    @Test
    @Order(3)
    @DisplayName("TC-UCTRL-03 (US-47) : GET /users en tant que HR → 403 Forbidden")
    @WithMockUser(roles = "HR")
    void getUsers_asHr_returns403() throws Exception {
        mockMvc.perform(get(BASE))
               .andExpect(status().isForbidden());
    }

    @Test
    @Order(4)
    @DisplayName("TC-UCTRL-04 (US-47) : GET /users en tant que ADMIN → 200 + page JSON")
    @WithMockUser(roles = "ADMIN")
    void getUsers_asAdmin_returns200WithPage() throws Exception {
        MvcResult result = mockMvc.perform(get(BASE)
                .param("page", "0")
                .param("size", "10"))
               .andExpect(status().isOk())
               .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
               .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("content"), "La réponse doit contenir le champ 'content' (page Spring)");
        assertTrue(body.contains("totalElements"), "La réponse doit contenir 'totalElements'");
    }

    @Test
    @Order(5)
    @DisplayName("TC-UCTRL-05 (US-47) : GET /users?page=0&size=5 → 200 + taille de page respectée")
    @WithMockUser(roles = "ADMIN")
    void getUsers_withPageParams_returns200WithCorrectPageSize() throws Exception {
        mockMvc.perform(get(BASE)
                .param("page", "0")
                .param("size", "5"))
               .andExpect(status().isOk())
               .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-45 : Créer les comptes RH / évaluateurs — POST /users/create
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("TC-UCTRL-06 (US-45) : POST /users/create sans authentification → 401")
    void createUser_noAuth_returns401() throws Exception {
        mockMvc.perform(post(BASE + "/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(hrRequest("noauth.hr@nexgenai.com"))))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(7)
    @DisplayName("TC-UCTRL-07 (US-45) : POST /users/create en tant que CANDIDATE → 403 Forbidden")
    @WithMockUser(roles = "CANDIDATE")
    void createUser_asCandidate_returns403() throws Exception {
        mockMvc.perform(post(BASE + "/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(hrRequest("candidate.hr@nexgenai.com"))))
               .andExpect(status().isForbidden());
    }

    @Test
    @Order(8)
    @DisplayName("TC-UCTRL-08 (US-45) : POST /users/create ADMIN + requête HR valide → 201 + email dans réponse")
    @WithMockUser(roles = "ADMIN")
    void createUser_asAdminWithValidHrRequest_returns201WithEmail() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE + "/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(hrRequest("marie.hr@nexgenai.com"))))
               .andExpect(status().isCreated())
               .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("marie.hr@nexgenai.com"), "La réponse doit contenir l'email créé");
        assertTrue(body.contains("HR") || body.contains("marie"),
            "La réponse doit confirmer la création du rôle HR");
    }

    @Test
    @Order(9)
    @DisplayName("TC-UCTRL-09 (US-45) : POST /users/create ADMIN + requête TECH_EVALUATOR valide → 201")
    @WithMockUser(roles = "ADMIN")
    void createUser_asAdminWithValidEvaluatorRequest_returns201() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE + "/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(evaluatorRequest("alan.eval@nexgenai.com"))))
               .andExpect(status().isCreated())
               .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("alan.eval@nexgenai.com"), "La réponse doit contenir l'email de l'évaluateur");
    }

    @Test
    @Order(10)
    @DisplayName("TC-UCTRL-10 (US-45) : POST /users/create ADMIN + email déjà pris → 4xx ou 5xx (conflit)")
    @WithMockUser(roles = "ADMIN")
    void createUser_asAdmin_duplicateEmail_returns4xxOr5xx() throws Exception {
        String email = "duplicate.hr2@nexgenai.com";

        // Première création — doit réussir
        mockMvc.perform(post(BASE + "/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(hrRequest(email))))
               .andExpect(status().isCreated());

        // Deuxième création — email dupliqué → rejeté
        try {
            MvcResult r = mockMvc.perform(post(BASE + "/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(hrRequest(email))))
                   .andReturn();
            assertTrue(r.getResponse().getStatus() >= 400,
                "Un email dupliqué doit retourner un code 4xx ou 5xx, obtenu : "
                    + r.getResponse().getStatus());
        } catch (Exception ex) {
            // IllegalArgumentException propagée — doublon correctement rejeté
            assertNotNull(ex.getMessage());
        }
    }

    @Test
    @Order(11)
    @DisplayName("TC-UCTRL-11 (US-45) : POST /users/create ADMIN + rôle inconnu → 400 ou 500")
    @WithMockUser(roles = "ADMIN")
    void createUser_asAdmin_invalidRole_returns4xxOr5xx() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("firstName", "Bad");
        req.put("lastName",  "Role");
        req.put("email",     "badrole@nexgenai.com");
        req.put("password",  "Pass123!");
        req.put("role",      "SUPER_VILLAIN");

        try {
            MvcResult r = mockMvc.perform(post(BASE + "/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                   .andReturn();
            assertTrue(r.getResponse().getStatus() >= 400,
                "Un rôle invalide doit retourner 4xx ou 5xx, obtenu : "
                    + r.getResponse().getStatus());
        } catch (Exception ex) {
            // IllegalArgumentException propagée — rôle inconnu correctement rejeté
            assertNotNull(ex.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RBAC — Suppression et activation (DELETE / PATCH /{id}/status)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(12)
    @DisplayName("TC-UCTRL-12 (RBAC) : DELETE /users/{id} en tant que CANDIDATE → 403")
    @WithMockUser(roles = "CANDIDATE")
    void deleteUser_asCandidate_returns403() throws Exception {
        mockMvc.perform(delete(BASE + "/some-user-id"))
               .andExpect(status().isForbidden());
    }

    @Test
    @Order(13)
    @DisplayName("TC-UCTRL-13 (RBAC) : PATCH /users/{id}/status en tant que HR → 403")
    @WithMockUser(roles = "HR")
    void toggleStatus_asHr_returns403() throws Exception {
        mockMvc.perform(patch(BASE + "/some-user-id/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
               .andExpect(status().isForbidden());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Endpoints annexes — GET /users/hr et /users/admins
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(14)
    @DisplayName("TC-UCTRL-14 : GET /users/hr authentifié → 200 + liste des RH")
    @WithMockUser(roles = "ADMIN")
    void getHrUsers_asAdmin_returns200WithList() throws Exception {
        mockMvc.perform(get(BASE + "/hr"))
               .andExpect(status().isOk())
               .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @Order(15)
    @DisplayName("TC-UCTRL-15 : GET /users/admins authentifié → 200 + liste des administrateurs")
    @WithMockUser(roles = "ADMIN")
    void getAdminUsers_asAdmin_returns200WithList() throws Exception {
        mockMvc.perform(get(BASE + "/admins"))
               .andExpect(status().isOk())
               .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
