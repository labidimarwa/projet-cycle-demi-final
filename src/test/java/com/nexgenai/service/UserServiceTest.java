package com.nexgenai.service;

import com.nexgenai.dto.evaluator.EvaluatorSummaryDTO;
import com.nexgenai.dto.user.CreateUserRequest;
import com.nexgenai.dto.user.CreateUserResponse;
import com.nexgenai.model.*;
import com.nexgenai.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires de UserService.
 *
 * Couvre :
 *   - Création d'utilisateur (HR, TECH_EVALUATOR, rôle invalide, email dupliqué)
 *   - Suppression d'utilisateur
 *   - Toggle de statut (actif/inactif)
 *   - Liste des utilisateurs HR, Admin, paginée
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — Tests Unitaires")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserServiceTest {

    // ── Mocks ─────────────────────────────────────────────────────────────────
    @Mock private UserRepository          userRepository;
    @Mock private HRRepository            hrRepository;
    @Mock private TechEvaluatorRepository techEvaluatorRepository;
    @Mock private PasswordEncoder         passwordEncoder;
    @Mock private EmailService            emailService;

    @InjectMocks private UserService userService;

    // ── Données de test ───────────────────────────────────────────────────────
    private CreateUserRequest hrRequest;
    private CreateUserRequest techEvaluatorRequest;

    @BeforeEach
    void setUp() {
        hrRequest = new CreateUserRequest();
        hrRequest.setFirstName("Marie");
        hrRequest.setLastName("Curie");
        hrRequest.setEmail("marie@nexgenai.com");
        hrRequest.setPassword("Secure123!");
        hrRequest.setRole("HR");
        hrRequest.setDepartment("Talent Acquisition");
        hrRequest.setPosition("HR Manager");

        techEvaluatorRequest = new CreateUserRequest();
        techEvaluatorRequest.setFirstName("Alan");
        techEvaluatorRequest.setLastName("Turing");
        techEvaluatorRequest.setEmail("alan@nexgenai.com");
        techEvaluatorRequest.setPassword("Secure456!");
        techEvaluatorRequest.setRole("TECH_EVALUATOR");
        techEvaluatorRequest.setSpecialization("DEVELOPER");
        techEvaluatorRequest.setTitle("Tech Lead");
        techEvaluatorRequest.setExpertiseLevel("SENIOR");
        techEvaluatorRequest.setYearsOfExperience(8);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // createUser — scénarios positifs
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-US-01 : createUser(HR) → crée un objet HR avec department et position")
    void createUser_hrRole_createsHrWithDepartmentAndPosition() {
        // GIVEN
        HR savedHr = new HR();
        savedHr.setId("hr-001");
        savedHr.setFirstName("Marie");
        savedHr.setLastName("Curie");
        savedHr.setEmail("marie@nexgenai.com");
        savedHr.setDepartment("Talent Acquisition");
        savedHr.setPosition("HR Manager");
        savedHr.setActive(true);

        when(userRepository.existsByEmail("marie@nexgenai.com")).thenReturn(false);
        when(passwordEncoder.encode("Secure123!")).thenReturn("$encoded");
        when(userRepository.save(any(User.class))).thenReturn(savedHr);
        doNothing().when(emailService).sendEmail(any(), any(), any());

        // WHEN
        CreateUserResponse response = userService.createUser(hrRequest);

        // THEN
        assertNotNull(response);
        assertEquals("hr-001",             response.getId());
        assertEquals("marie@nexgenai.com", response.getEmail());
        assertEquals("HR",                 response.getRole());

        // Vérifier que l'entité sauvegardée est bien un HR avec les bons champs
        verify(userRepository).save(argThat(user -> {
            assertTrue(user instanceof HR, "L'entité créée doit être une instance de HR");
            HR hr = (HR) user;
            assertEquals("Talent Acquisition", hr.getDepartment());
            assertEquals("HR Manager",         hr.getPosition());
            return true;
        }));
    }

    @Test
    @Order(2)
    @DisplayName("TC-US-02 : createUser(TECH_EVALUATOR) → crée TechEvaluator avec valeurs par défaut")
    void createUser_techEvaluatorRole_createsTechEvaluatorWithDefaultValues() {
        // GIVEN
        TechEvaluator savedEval = new TechEvaluator();
        savedEval.setId("eval-001");
        savedEval.setFirstName("Alan");
        savedEval.setLastName("Turing");
        savedEval.setEmail("alan@nexgenai.com");
        savedEval.setActive(true);

        when(userRepository.existsByEmail("alan@nexgenai.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("$enc");
        when(userRepository.save(any(User.class))).thenReturn(savedEval);
        doNothing().when(emailService).sendEmail(any(), any(), any());

        // WHEN
        userService.createUser(techEvaluatorRequest);

        // THEN : vérifier valeurs par défaut
        verify(userRepository).save(argThat(user -> {
            assertTrue(user instanceof TechEvaluator);
            TechEvaluator te = (TechEvaluator) user;
            assertEquals(3,    te.getMaxEvaluationsPerDay(),  "maxEvaluationsPerDay doit valoir 3");
            assertEquals(0,    te.getEvaluationsToday(),      "evaluationsToday doit valoir 0");
            assertEquals(0,    te.getTotalEvaluations(),      "totalEvaluations doit valoir 0");
            assertTrue(te.isCanCreateTechnicalTests());
            assertTrue(te.isCanGradeTests());
            assertTrue(te.isCanConductInterviews());
            return true;
        }));
    }

    @Test
    @Order(3)
    @DisplayName("TC-US-03 : createUser → le mot de passe est encodé avant sauvegarde")
    void createUser_passwordIsEncodedBeforeSave() {
        // GIVEN
        HR hr = new HR();
        hr.setId("x");
        hr.setEmail("marie@nexgenai.com");
        hr.setFirstName("Marie");
        hr.setLastName("Curie");
        hr.setActive(true);

        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(passwordEncoder.encode("Secure123!")).thenReturn("$2a$ENCODED");
        when(userRepository.save(any())).thenReturn(hr);
        doNothing().when(emailService).sendEmail(any(), any(), any());

        // WHEN
        userService.createUser(hrRequest);

        // THEN
        verify(userRepository).save(argThat(u -> "$2a$ENCODED".equals(u.getPassword())));
    }

    @Test
    @Order(4)
    @DisplayName("TC-US-04 : createUser → email de bienvenue envoyé après création")
    void createUser_sendsWelcomeEmailAfterCreation() {
        // GIVEN
        HR hr = new HR();
        hr.setId("x");
        hr.setEmail("marie@nexgenai.com");
        hr.setFirstName("Marie");
        hr.setLastName("Curie");
        hr.setActive(true);

        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("$enc");
        when(userRepository.save(any())).thenReturn(hr);

        // WHEN
        userService.createUser(hrRequest);

        // THEN
        verify(emailService, times(1))
            .sendEmail(eq("marie@nexgenai.com"), any(String.class), any(String.class));
    }

    @Test
    @Order(5)
    @DisplayName("TC-US-05 : createUser → createdAt et isActive positionnés")
    void createUser_setsCreatedAtAndIsActive() {
        // GIVEN
        HR hr = new HR();
        hr.setId("x");
        hr.setEmail("marie@nexgenai.com");
        hr.setFirstName("Marie");
        hr.setLastName("Curie");
        hr.setActive(true);

        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("$enc");
        when(userRepository.save(any())).thenReturn(hr);
        doNothing().when(emailService).sendEmail(any(), any(), any());

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        // WHEN
        userService.createUser(hrRequest);

        LocalDateTime after = LocalDateTime.now().plusSeconds(1);

        // THEN
        verify(userRepository).save(argThat(u -> {
            assertTrue(u.isActive(), "L'utilisateur doit être actif");
            assertNotNull(u.getCreatedAt(), "createdAt doit être défini");
            assertTrue(u.getCreatedAt().isAfter(before) && u.getCreatedAt().isBefore(after));
            return true;
        }));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // createUser — scénarios négatifs
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("TC-US-06 : createUser avec rôle invalide → IllegalArgumentException")
    void createUser_withInvalidRole_throwsIllegalArgumentException() {
        // GIVEN
        CreateUserRequest request = new CreateUserRequest();
        request.setEmail("test@test.com");
        request.setPassword("P@ssw0rd!");
        request.setFirstName("X");
        request.setLastName("Y");
        request.setRole("INVALID_ROLE");

        when(userRepository.existsByEmail(any())).thenReturn(false);

        // WHEN + THEN
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> userService.createUser(request)
        );
        assertTrue(ex.getMessage().contains("INVALID_ROLE"),
            "Le message doit mentionner le rôle invalide");
    }

    @Test
    @Order(7)
    @DisplayName("TC-US-07 : createUser avec email déjà pris → IllegalArgumentException, pas de save")
    void createUser_duplicateEmail_throwsIllegalArgumentExceptionAndNeverSaves() {
        // GIVEN
        when(userRepository.existsByEmail("marie@nexgenai.com")).thenReturn(true);

        // WHEN + THEN
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> userService.createUser(hrRequest)
        );
        assertTrue(ex.getMessage().contains("marie@nexgenai.com"));
        verify(userRepository, never()).save(any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // deleteUser
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(8)
    @DisplayName("TC-US-08 : deleteUser(id existant) → suppression réussie")
    void deleteUser_existingId_deletesSuccessfully() {
        // GIVEN
        HR hr = new HR();
        hr.setId("hr-001");
        when(userRepository.findById("hr-001")).thenReturn(Optional.of(hr));
        doNothing().when(userRepository).delete(hr);

        // WHEN + THEN
        assertDoesNotThrow(() -> userService.deleteUser("hr-001"));
        verify(userRepository, times(1)).delete(hr);
    }

    @Test
    @Order(9)
    @DisplayName("TC-US-09 : deleteUser(id inexistant) → IllegalArgumentException, delete jamais appelé")
    void deleteUser_nonExistingId_throwsIllegalArgumentException() {
        // GIVEN
        when(userRepository.findById("bad-id")).thenReturn(Optional.empty());

        // WHEN + THEN
        assertThrows(IllegalArgumentException.class,
            () -> userService.deleteUser("bad-id"));
        verify(userRepository, never()).delete(any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // toggleUserStatus
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("TC-US-10 : toggleUserStatus(id, true) → utilisateur activé et sauvegardé")
    void toggleUserStatus_setActiveTrue_updatesUser() {
        // GIVEN
        HR hr = new HR();
        hr.setId("hr-001");
        hr.setActive(false);

        when(userRepository.findById("hr-001")).thenReturn(Optional.of(hr));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // WHEN
        userService.toggleUserStatus("hr-001", true);

        // THEN
        verify(userRepository).save(argThat(u -> u.isActive() == true));
    }

    @Test
    @Order(11)
    @DisplayName("TC-US-11 : toggleUserStatus(id, false) → utilisateur désactivé et sauvegardé")
    void toggleUserStatus_setActiveFalse_deactivatesUser() {
        // GIVEN
        HR hr = new HR();
        hr.setId("hr-001");
        hr.setActive(true);

        when(userRepository.findById("hr-001")).thenReturn(Optional.of(hr));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // WHEN
        userService.toggleUserStatus("hr-001", false);

        // THEN
        verify(userRepository).save(argThat(u -> u.isActive() == false));
    }

    @Test
    @Order(12)
    @DisplayName("TC-US-12 : toggleUserStatus(id inexistant) → IllegalArgumentException")
    void toggleUserStatus_nonExistingId_throwsIllegalArgumentException() {
        when(userRepository.findById("xxx")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
            () -> userService.toggleUserStatus("xxx", true));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getAllHrUsers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(13)
    @DisplayName("TC-US-13 : getAllHrUsers → retourne la liste des HR avec fullName, email, department, role")
    void getAllHrUsers_returnsListWithCorrectMapping() {
        // GIVEN
        HR hr1 = new HR();
        hr1.setId("hr-001");
        hr1.setFirstName("Alice");
        hr1.setLastName("Martin");
        hr1.setEmail("alice.hr@test.com");
        hr1.setDepartment("IT");

        HR hr2 = new HR();
        hr2.setId("hr-002");
        hr2.setFirstName("Bob");
        hr2.setLastName("Smith");
        hr2.setEmail("bob.hr@test.com");
        hr2.setDepartment("Finance");

        when(hrRepository.findAll()).thenReturn(List.of(hr1, hr2));

        // WHEN
        List<EvaluatorSummaryDTO> result = userService.getAllHrUsers();

        // THEN
        assertEquals(2, result.size());
        assertEquals("hr-001",           result.get(0).getId());
        assertEquals("Alice Martin",     result.get(0).getFullName());
        assertEquals("alice.hr@test.com",result.get(0).getEmail());
        assertEquals("IT",               result.get(0).getDepartment());
        assertEquals("HR",               result.get(0).getRole());
    }

    @Test
    @Order(14)
    @DisplayName("TC-US-14 : getAllHrUsers liste vide → retourne liste vide")
    void getAllHrUsers_empty_returnsEmptyList() {
        when(hrRepository.findAll()).thenReturn(List.of());
        assertTrue(userService.getAllHrUsers().isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // getAllAdminUsers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(15)
    @DisplayName("TC-US-15 : getAllAdminUsers → filtre uniquement les Admin")
    void getAllAdminUsers_filtersOnlyAdminUsers() {
        // GIVEN : un Admin et un Candidate dans le repo
        Admin admin = new Admin();
        admin.setId("admin-001");
        admin.setFirstName("Super");
        admin.setLastName("Admin");
        admin.setEmail("admin@nexgenai.com");

        Candidate candidate = new Candidate();
        candidate.setId("cand-001");
        candidate.setFirstName("John");
        candidate.setLastName("Doe");
        candidate.setEmail("john@test.com");

        when(userRepository.findAll()).thenReturn(List.of(admin, candidate));

        // WHEN
        List<EvaluatorSummaryDTO> result = userService.getAllAdminUsers();

        // THEN
        assertEquals(1, result.size(), "Seul l'Admin doit être retourné");
        assertEquals("admin-001",         result.get(0).getId());
        assertEquals("Super Admin",       result.get(0).getFullName());
        assertEquals("ADMIN",             result.get(0).getRole());
    }

    @Test
    @Order(16)
    @DisplayName("TC-US-16 : getUsers → retourne une page de UserListResponse")
    void getUsers_returnsPaginatedResults() {
        // GIVEN
        HR hr = new HR();
        hr.setId("hr-001");
        hr.setFirstName("Alice");
        hr.setLastName("Martin");
        hr.setEmail("alice@test.com");
        hr.setActive(true);
        hr.setCreatedAt(LocalDateTime.now());

        Pageable pageable = PageRequest.of(0, 10);
        Page<User> page = new PageImpl<>(List.of(hr), pageable, 1);
        when(userRepository.findAll(pageable)).thenReturn(page);

        // WHEN
        var result = userService.getUsers(pageable);

        // THEN
        assertEquals(1, result.getTotalElements());
        assertEquals("alice@test.com", result.getContent().get(0).getEmail());
        assertEquals("HR",             result.getContent().get(0).getUserType());
    }
}
