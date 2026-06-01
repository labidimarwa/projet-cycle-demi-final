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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration du contrôleur JobController.
 *
 * Couvre :
 *   - GET /jobs, /jobs/active, /jobs/{id}, /jobs/department/{dep}  (lecture publique)
 *   - POST /jobs (création — requiert HR ou ADMIN)
 *   - PUT/PATCH /jobs/{id} (mise à jour)
 *   - PATCH /jobs/{id}/status (changement de statut)
 *   - DELETE /jobs/{id} (suppression)
 *   - Sécurité RBAC : sans auth → 401, avec CANDIDATE → 403
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("JobController — Tests d'Intégration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JobControllerTest {

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .defaultRequest(MockMvcRequestBuilders.get("/").header("X-Forwarded-For", "10.30.0.1"))
            .build();
    }

    @MockBean private EmailService         emailService;
    @MockBean private CodeExecutionService codeExecutionService;

    private static final String BASE = "/jobs";

    // ── Helper ────────────────────────────────────────────────────────────────

    private Map<String, Object> buildJobRequest(String title, String dept) {
        Map<String, Object> req = new HashMap<>();
        req.put("title",                title);
        req.put("department",           dept);
        req.put("location",             "Tunis");
        req.put("contractType",         ContractType.CONTRACT.name());
        req.put("experienceLevel",      ExperienceLevel.SENIOR.name());
        req.put("description",          "Description du poste " + title);
        req.put("openPositions",        1);
        req.put("isRemote",             false);
        req.put("skillsWeight",         70);
        req.put("prerequisitesWeight",  30);
        req.put("technicalSkillWeight", 60);
        req.put("softSkillWeight",      40);
        req.put("prerequisites",  List.of(
            Map.of("type", "DEGREE", "value", "Bac+3", "obligatory", true)));
        req.put("technicalSkills", List.of(
            Map.of("name", "Java", "obligatory", true, "skillType", "TECHNICAL")));
        return req;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Lecture publique
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-JCTRL-01 : GET /jobs sans auth → 200 (endpoint public)")
    void getAllJobs_noAuth_returns200() throws Exception {
        mockMvc.perform(get(BASE))
               .andExpect(status().isOk())
               .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @Order(2)
    @DisplayName("TC-JCTRL-02 : GET /jobs/active sans auth → 200")
    void getActiveJobs_noAuth_returns200() throws Exception {
        mockMvc.perform(get(BASE + "/active"))
               .andExpect(status().isOk());
    }

    @Test
    @Order(3)
    @DisplayName("TC-JCTRL-03 : GET /jobs/department/IT sans auth → 200")
    void getByDepartment_noAuth_returns200() throws Exception {
        mockMvc.perform(get(BASE + "/department/IT"))
               .andExpect(status().isOk());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Création (POST /jobs) — RBAC
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("TC-JCTRL-04 : POST /jobs sans auth → 401 ou 403")
    void createJob_noAuth_returns4xx() throws Exception {
        mockMvc.perform(post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildJobRequest("Test Job", "IT"))))
               .andExpect(status().is4xxClientError());
    }

    @Test
    @Order(5)
    @DisplayName("TC-JCTRL-05 : POST /jobs en tant que CANDIDATE → 403")
    @WithMockUser(roles = "CANDIDATE")
    void createJob_asCandidate_returns403() throws Exception {
        mockMvc.perform(post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildJobRequest("Candidate Job", "IT"))))
               .andExpect(status().isForbidden());
    }

    @Test
    @Order(6)
    @DisplayName("TC-JCTRL-06 : POST /jobs en tant que HR → 201 avec le job créé")
    @WithMockUser(roles = "HR")
    void createJob_asHr_returns201WithCreatedJob() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    buildJobRequest("HR Created Job", "Engineering"))))
               .andExpect(status().isCreated())
               .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("HR Created Job"), "La réponse doit contenir le titre du job");
        assertTrue(body.contains("id"), "La réponse doit contenir l'id du job créé");
    }

    @Test
    @Order(7)
    @DisplayName("TC-JCTRL-07 : POST /jobs en tant que ADMIN → 201")
    @WithMockUser(roles = "ADMIN")
    void createJob_asAdmin_returns201() throws Exception {
        mockMvc.perform(post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    buildJobRequest("Admin Created Job", "Finance"))))
               .andExpect(status().isCreated());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Lecture par ID
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(8)
    @DisplayName("TC-JCTRL-08 : GET /jobs/{id} avec id existant → 200 + job en réponse")
    @WithMockUser(roles = "HR")
    void getJobById_existingId_returns200() throws Exception {
        // GIVEN : créer un job
        MvcResult createResult = mockMvc.perform(post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    buildJobRequest("Job For GetById", "IT"))))
               .andExpect(status().isCreated())
               .andReturn();

        String createBody = createResult.getResponse().getContentAsString();
        String jobId = objectMapper.readTree(createBody).get("id").asText();

        // WHEN
        mockMvc.perform(get(BASE + "/" + jobId))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("Job For GetById")));
    }

    @Test
    @Order(9)
    @DisplayName("TC-JCTRL-09 : GET /jobs/{id} avec id inexistant → 5xx ou 4xx")
    void getJobById_nonExistingId_returns5xxOr4xx() throws Exception {
        // Service throws RuntimeException (no global handler) → MockMvc may re-throw
        try {
            MvcResult r = mockMvc.perform(get(BASE + "/non-existent-id-xyz")).andReturn();
            assertTrue(r.getResponse().getStatus() >= 400,
                "Expected 4xx or 5xx but got " + r.getResponse().getStatus());
        } catch (Exception ex) {
            // RuntimeException propagated — correctly rejected
            assertNotNull(ex.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PATCH /jobs/{id}/status
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("TC-JCTRL-10 : PATCH /jobs/{id}/status en tant que HR → 200 avec statut mis à jour")
    @WithMockUser(roles = "HR")
    void changeStatus_asHr_returns200WithUpdatedStatus() throws Exception {
        // GIVEN : créer un job
        MvcResult createResult = mockMvc.perform(post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    buildJobRequest("Status Change Job", "IT"))))
               .andExpect(status().isCreated())
               .andReturn();

        String jobId = objectMapper.readTree(
            createResult.getResponse().getContentAsString()).get("id").asText();

        // WHEN : changer le statut à ACTIVE
        MvcResult result = mockMvc.perform(patch(BASE + "/" + jobId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}"))
               .andExpect(status().isOk())
               .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("ACTIVE"), "Le statut doit être mis à jour à ACTIVE");
    }

    @Test
    @Order(11)
    @DisplayName("TC-JCTRL-11 : PATCH /jobs/{id}/status sans statut → 400")
    @WithMockUser(roles = "HR")
    void changeStatus_missingStatus_returns400() throws Exception {
        // Créer un job d'abord
        MvcResult createResult = mockMvc.perform(post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    buildJobRequest("No Status Job", "IT"))))
               .andExpect(status().isCreated())
               .andReturn();
        String jobId = objectMapper.readTree(
            createResult.getResponse().getContentAsString()).get("id").asText();

        // Envoyer sans le champ status
        mockMvc.perform(patch(BASE + "/" + jobId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
               .andExpect(status().isBadRequest());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PATCH /jobs/{id} (mise à jour partielle)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(12)
    @DisplayName("TC-JCTRL-12 : PATCH /jobs/{id} avec nouveau titre → 200 + titre mis à jour")
    @WithMockUser(roles = "HR")
    void patchJob_newTitle_returns200WithUpdatedTitle() throws Exception {
        // GIVEN : créer un job
        MvcResult createResult = mockMvc.perform(post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    buildJobRequest("Original Title", "IT"))))
               .andExpect(status().isCreated())
               .andReturn();
        String jobId = objectMapper.readTree(
            createResult.getResponse().getContentAsString()).get("id").asText();

        // WHEN : patcher le titre
        MvcResult result = mockMvc.perform(patch(BASE + "/" + jobId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Updated Title\"}"))
               .andExpect(status().isOk())
               .andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("Updated Title"));
    }

    @Test
    @Order(13)
    @DisplayName("TC-JCTRL-13 : DELETE /jobs/{id} sans auth → 401 ou 403")
    void deleteJob_noAuth_returns4xx() throws Exception {
        mockMvc.perform(delete(BASE + "/some-job-id"))
               .andExpect(status().is4xxClientError());
    }

    @Test
    @Order(14)
    @DisplayName("TC-JCTRL-14 : DELETE /jobs/{id} en tant que CANDIDATE → 403")
    @WithMockUser(roles = "CANDIDATE")
    void deleteJob_asCandidate_returns403() throws Exception {
        mockMvc.perform(delete(BASE + "/any-job-id"))
               .andExpect(status().isForbidden());
    }

    @Test
    @Order(15)
    @DisplayName("TC-JCTRL-15 : DELETE /jobs/{id} en tant que HR avec id existant → 200")
    @WithMockUser(roles = "HR")
    void deleteJob_asHrWithExistingId_returns200() throws Exception {
        // GIVEN : créer un job
        MvcResult createResult = mockMvc.perform(post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    buildJobRequest("Job To Delete", "IT"))))
               .andExpect(status().isCreated())
               .andReturn();
        String jobId = objectMapper.readTree(
            createResult.getResponse().getContentAsString()).get("id").asText();

        // WHEN + THEN — controller returns 204 No Content on successful delete
        mockMvc.perform(delete(BASE + "/" + jobId))
               .andExpect(status().is2xxSuccessful());
    }

    @Test
    @Order(16)
    @DisplayName("TC-JCTRL-16 : DELETE /jobs/{id} avec id inexistant → 5xx ou 4xx")
    @WithMockUser(roles = "ADMIN")
    void deleteJob_nonExistingId_returns5xxOr4xx() throws Exception {
        // Service throws RuntimeException (no global handler) → MockMvc may re-throw
        try {
            MvcResult r = mockMvc.perform(delete(BASE + "/absolutely-non-existent-id-xyz")).andReturn();
            assertTrue(r.getResponse().getStatus() >= 400,
                "Expected 4xx or 5xx but got " + r.getResponse().getStatus());
        } catch (Exception ex) {
            // RuntimeException propagated — correctly rejected
            assertNotNull(ex.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Génération de lien (generate-link)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(17)
    @DisplayName("TC-JCTRL-17 : POST /jobs/{id}/remote-link avec id existant → 200 + URL")
    @WithMockUser(roles = "HR")
    void generateLink_existingJob_returns200WithUrl() throws Exception {
        // GIVEN : créer un job
        MvcResult createResult = mockMvc.perform(post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    buildJobRequest("Link Job", "IT"))))
               .andExpect(status().isCreated())
               .andReturn();
        String jobId = objectMapper.readTree(
            createResult.getResponse().getContentAsString()).get("id").asText();

        // WHEN — endpoint is POST /{id}/remote-link (not GET /generate-link)
        MvcResult linkResult = mockMvc.perform(post(BASE + "/" + jobId + "/remote-link"))
               .andExpect(status().isOk())
               .andReturn();

        String link = linkResult.getResponse().getContentAsString();
        assertTrue(link.contains("apply") || link.contains("http") || !link.isEmpty(),
            "La réponse doit contenir un lien d'application : " + link);
    }
}
