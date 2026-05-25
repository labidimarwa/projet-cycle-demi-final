package com.nexgenai.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexgenai.service.CodeExecutionService;
import com.nexgenai.service.EmailService;
import com.nexgenai.service.OllamaMatchingService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
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
 * Tests E2E du flux d'authentification complet.
 *
 * Scénarios bout-en-bout :
 *   1. Inscription → Connexion → Accès à un endpoint protégé avec le token JWT
 *   2. Token invalide → rejet (401) sur endpoint protégé
 *   3. Admin par défaut (créé par AdminInitializer) → login → accès admin
 *
 * Ces tests valident l'intégration complète : AuthController → AuthService
 * → UserRepository (H2) → JwtService → JwtAuthenticationFilter → SecurityConfig.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Auth Flow — Tests E2E")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullAuthFlowTest {

    // Built manually to set a unique X-Forwarded-For so the RateLimitingFilter
    // does not share the IP counter with other test classes (AUTH_MAX = 10/min).
    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .defaultRequest(MockMvcRequestBuilders.get("/").header("X-Forwarded-For", "10.10.0.1"))
            .build();
    }

    @MockBean private EmailService          emailService;
    @MockBean private OllamaMatchingService ollamaMatchingService;
    @MockBean private CodeExecutionService  codeExecutionService;

    private static final String AUTH_BASE = "/auth";
    private static final String JOBS_BASE = "/jobs";

    // ══════════════════════════════════════════════════════════════════════════
    // Flux 1 : Register → Login → Access Protected Endpoint
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-E2E-01 : Register → Login → GET /job-tests avec token → 200 ou 404 (mais pas 401/403)")
    void registerThenLoginThenAccessProtectedEndpoint_fullFlow() throws Exception {
        String email    = "e2e.flow@test.com";
        String password = "E2ePassword1!";

        // ── Étape 1 : Inscription ──────────────────────────────────────────────
        Map<String, String> registerReq = Map.of(
            "firstName", "E2E", "lastName", "Flow",
            "email", email, "password", password, "confirmPassword", password
        );

        MvcResult registerResult = mockMvc.perform(post(AUTH_BASE + "/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String registerBody = registerResult.getResponse().getContentAsString();
        assertNotNull(registerBody);
        assertTrue(registerBody.contains("token"), "L'inscription doit retourner un token");

        // ── Étape 2 : Connexion ────────────────────────────────────────────────
        Map<String, String> loginReq = Map.of("email", email, "password", password);

        MvcResult loginResult = mockMvc.perform(post(AUTH_BASE + "/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();

        String loginBody = loginResult.getResponse().getContentAsString();
        JsonNode loginJson = objectMapper.readTree(loginBody);
        String jwtToken = loginJson.get("token").asText();

        assertNotNull(jwtToken, "Le login doit retourner un token JWT");
        assertFalse(jwtToken.isBlank());
        // Vérifier la structure JWT (header.payload.signature)
        assertEquals(3, jwtToken.split("\\.").length,
            "Le token JWT doit avoir 3 parties séparées par des points");

        // ── Étape 3 : Accès à un endpoint protégé avec le token ───────────────
        MvcResult protectedResult = mockMvc.perform(get("/job-tests")
                .header("Authorization", "Bearer " + jwtToken))
                .andReturn();

        int status = protectedResult.getResponse().getStatus();
        // L'utilisateur est authentifié → NE DOIT PAS être 401 (non-authentifié)
        // Peut être 200 (données) ou 404 (pas de données) mais pas 401
        assertNotEquals(401, status,
            "Un utilisateur authentifié ne doit pas recevoir 401");
        assertNotEquals(403, status,
            "Un CANDIDATE peut accéder à GET /job-tests (lecture publique/authentifiée)");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Flux 2 : Token invalide → rejet 401
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("TC-E2E-02 : Token JWT invalide → endpoint protégé retourne 401")
    void invalidToken_protectedEndpoint_returns401() throws Exception {
        String invalidToken = "Bearer this.is.not.a.valid.jwt.token.at.all";

        mockMvc.perform(get("/admin/users")
                .header("Authorization", invalidToken))
               .andExpect(status().is4xxClientError());
    }

    @Test
    @Order(3)
    @DisplayName("TC-E2E-03 : Sans token → /admin/users retourne 401")
    void noToken_adminEndpoint_returns401() throws Exception {
        mockMvc.perform(get("/admin/users"))
               .andExpect(status().is4xxClientError());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Flux 3 : Le token du register est immédiatement utilisable
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("TC-E2E-04 : Token du register utilisable immédiatement pour les endpoints candidat")
    void registerToken_immediatelyUsableForCandidateEndpoints() throws Exception {
        String email    = "e2e.immediate@test.com";
        String password = "ImmediateToken1!";

        // Inscription
        Map<String, String> registerReq = Map.of(
            "firstName", "Immed", "lastName", "Test",
            "email", email, "password", password, "confirmPassword", password
        );

        MvcResult result = mockMvc.perform(post(AUTH_BASE + "/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerReq)))
                .andExpect(status().isCreated())
                .andReturn();

        // Extraire le token du register
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = json.get("token").asText();

        // Utiliser immédiatement ce token pour accéder à un endpoint CANDIDATE
        MvcResult candidateResult = mockMvc.perform(get("/candidate/applications")
                .header("Authorization", "Bearer " + token))
                .andReturn();

        int status = candidateResult.getResponse().getStatus();
        // 200 (données vides) ou 404 sont acceptables, pas 401
        assertNotEquals(401, status,
            "Le token du register doit être valide immédiatement");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Flux 4 : Refresh token présent et différent du access token
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("TC-E2E-05 : Login retourne à la fois un token et un refreshToken distincts")
    void login_returnsDistinctAccessAndRefreshTokens() throws Exception {
        String email    = "e2e.refresh@test.com";
        String password = "RefreshToken1!";

        // Register
        Map<String, String> registerReq = Map.of(
            "firstName", "Refresh", "lastName", "Test",
            "email", email, "password", password, "confirmPassword", password
        );
        mockMvc.perform(post(AUTH_BASE + "/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerReq)))
                .andExpect(status().isCreated());

        // Login
        Map<String, String> loginReq = Map.of("email", email, "password", password);
        MvcResult loginResult = mockMvc.perform(post(AUTH_BASE + "/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken  = json.get("token").asText();
        String refreshToken = json.get("refreshToken").asText();

        assertNotNull(accessToken,  "Le access token doit être présent");
        assertNotNull(refreshToken, "Le refresh token doit être présent");
        assertNotEquals(accessToken, refreshToken,
            "Le access token et le refresh token doivent être distincts");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Flux 5 : CANDIDATE ne peut pas accéder à /admin
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("TC-E2E-06 : CANDIDATE avec token valide → /admin/users retourne 403")
    void candidateWithValidToken_adminEndpoint_returns403() throws Exception {
        String email    = "e2e.candidate@test.com";
        String password = "CandidateAccess1!";

        // Register + login
        Map<String, String> registerReq = Map.of(
            "firstName", "Cand", "lastName", "Access",
            "email", email, "password", password, "confirmPassword", password
        );
        mockMvc.perform(post(AUTH_BASE + "/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerReq)))
                .andExpect(status().isCreated());

        Map<String, String> loginReq = Map.of("email", email, "password", password);
        MvcResult loginResult = mockMvc.perform(post(AUTH_BASE + "/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(
            loginResult.getResponse().getContentAsString()).get("token").asText();

        // Le CANDIDATE avec un token valide ne doit PAS accéder à /admin
        mockMvc.perform(get("/admin/users")
                .header("Authorization", "Bearer " + token))
               .andExpect(status().isForbidden());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Flux 6 : Données retournées par login sont cohérentes
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("TC-E2E-07 : Login retourne id, email, firstName, lastName, userType, expiresIn")
    void login_responseContainsAllExpectedFields() throws Exception {
        String email    = "e2e.fields@test.com";
        String password = "FieldsTest1!";

        // Register
        Map<String, String> registerReq = Map.of(
            "firstName", "Fields", "lastName", "Test",
            "email", email, "password", password, "confirmPassword", password
        );
        mockMvc.perform(post(AUTH_BASE + "/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerReq)))
                .andExpect(status().isCreated());

        // Login
        Map<String, String> loginReq = Map.of("email", email, "password", password);
        MvcResult loginResult = mockMvc.perform(post(AUTH_BASE + "/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(loginResult.getResponse().getContentAsString());

        assertNotNull(json.get("id"),         "id doit être présent");
        assertEquals(email, json.get("email").asText());
        assertEquals("Fields", json.get("firstName").asText());
        assertEquals("Test",   json.get("lastName").asText());
        assertEquals("CANDIDATE", json.get("userType").asText());
        assertNotNull(json.get("expiresIn"),  "expiresIn doit être présent");
        assertTrue(json.get("expiresIn").asLong() > 0, "expiresIn doit être positif");
    }
}
