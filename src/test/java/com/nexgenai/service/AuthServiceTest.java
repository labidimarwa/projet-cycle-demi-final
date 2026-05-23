package com.nexgenai.service;

import com.nexgenai.dto.LoginRequest;
import com.nexgenai.dto.LoginResponse;
import com.nexgenai.dto.RegisterRequest;
import com.nexgenai.dto.RegisterResponse;
import com.nexgenai.model.Candidate;
import com.nexgenai.model.HR;
import com.nexgenai.model.TechEvaluator;
import com.nexgenai.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires d'AuthService — couvre login et registerCandidate.
 *
 * US concernées :
 *   - US-01 : Connexion utilisateur (login)
 *   - US-02 : Inscription candidat (register)
 *   - US-03 : Unicité email à l'inscription
 *   - US-04 : Tokens JWT générés à la connexion/inscription
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — Tests Unitaires")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthServiceTest {

    // ── Mocks ─────────────────────────────────────────────────────────────────
    @Mock private AuthenticationManager authenticationManager;
    @Mock private UserRepository        userRepository;
    @Mock private JwtService            jwtService;
    @Mock private PasswordEncoder       passwordEncoder;

    @InjectMocks private AuthService authService;

    // ── Données de test ───────────────────────────────────────────────────────
    private Candidate candidateUser;
    private HR        hrUser;
    private TechEvaluator evaluatorUser;
    private LoginRequest  loginRequest;
    private RegisterRequest registerRequest;

    @BeforeEach
    void setUp() {
        candidateUser = new Candidate();
        candidateUser.setId("cand-001");
        candidateUser.setFirstName("Alice");
        candidateUser.setLastName("Martin");
        candidateUser.setEmail("alice@test.com");
        candidateUser.setPassword("$2a$hashed");
        candidateUser.setActive(true);
        candidateUser.setCurrentPosition("Développeur Junior");
        candidateUser.setYearsOfExperience(2);
        candidateUser.setEducationLevel("Master");
        candidateUser.setCreatedAt(LocalDateTime.now());

        hrUser = new HR();
        hrUser.setId("hr-001");
        hrUser.setFirstName("Bob");
        hrUser.setLastName("Smith");
        hrUser.setEmail("hr@test.com");
        hrUser.setPassword("$2a$hashed");
        hrUser.setActive(true);
        hrUser.setDepartment("Recrutement");
        hrUser.setPosition("Responsable RH");
        hrUser.setCreatedAt(LocalDateTime.now());

        evaluatorUser = new TechEvaluator();
        evaluatorUser.setId("eval-001");
        evaluatorUser.setFirstName("Eve");
        evaluatorUser.setLastName("Dupont");
        evaluatorUser.setEmail("eval@test.com");
        evaluatorUser.setPassword("$2a$hashed");
        evaluatorUser.setActive(true);
        evaluatorUser.setSpecialization("DEVELOPER");
        evaluatorUser.setExpertiseLevel("SENIOR");
        evaluatorUser.setTitle("Tech Lead");
        evaluatorUser.setCreatedAt(LocalDateTime.now());

        loginRequest = new LoginRequest();
        loginRequest.setEmail("alice@test.com");
        loginRequest.setPassword("password123");

        registerRequest = new RegisterRequest();
        registerRequest.setFirstName("Charlie");
        registerRequest.setLastName("Brown");
        registerRequest.setEmail("charlie@test.com");
        registerRequest.setPassword("Password123!");
        registerRequest.setConfirmPassword("Password123!");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-01 : Login — scénarios positifs
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-AUTH-01 : login(Candidate) → retourne LoginResponse avec tokens et champs Candidate")
    void login_validCandidateCredentials_returnsCompleteLoginResponse() {
        // GIVEN
        when(authenticationManager.authenticate(any()))
            .thenReturn(new UsernamePasswordAuthenticationToken("alice@test.com", null));
        when(userRepository.findByEmail("alice@test.com"))
            .thenReturn(Optional.of(candidateUser));
        doNothing().when(userRepository).updateLastLogin(any(), any());
        when(jwtService.generateToken(candidateUser)).thenReturn("access-token-abc");
        when(jwtService.generateRefreshToken(candidateUser)).thenReturn("refresh-token-xyz");
        when(jwtService.getJwtExpiration()).thenReturn(86400000L);

        // WHEN
        LoginResponse response = authService.login(loginRequest);

        // THEN
        assertNotNull(response);
        assertEquals("access-token-abc",  response.getToken());
        assertEquals("refresh-token-xyz", response.getRefreshToken());
        assertEquals("cand-001",          response.getId());
        assertEquals("alice@test.com",    response.getEmail());
        assertEquals("Alice",             response.getFirstName());
        assertEquals("Martin",            response.getLastName());
        assertEquals("CANDIDATE",         response.getUserType());
        assertEquals(86400000L,           response.getExpiresIn());
        // Champs spécifiques au Candidate
        assertEquals("Développeur Junior", response.getCurrentPosition());
        assertEquals(2,                    response.getYearsOfExperience());
        assertEquals("Master",             response.getEducationLevel());
    }

    @Test
    @Order(2)
    @DisplayName("TC-AUTH-02 : login(HR) → LoginResponse inclut department et position")
    void login_hrUser_responsContainsDepartmentAndPosition() {
        // GIVEN
        loginRequest.setEmail("hr@test.com");
        when(authenticationManager.authenticate(any()))
            .thenReturn(new UsernamePasswordAuthenticationToken("hr@test.com", null));
        when(userRepository.findByEmail("hr@test.com"))
            .thenReturn(Optional.of(hrUser));
        doNothing().when(userRepository).updateLastLogin(any(), any());
        when(jwtService.generateToken(hrUser)).thenReturn("hr-token");
        when(jwtService.generateRefreshToken(hrUser)).thenReturn("hr-refresh");
        when(jwtService.getJwtExpiration()).thenReturn(86400000L);

        // WHEN
        LoginResponse response = authService.login(loginRequest);

        // THEN
        assertEquals("Recrutement",    response.getDepartment());
        assertEquals("Responsable RH", response.getPosition());
        assertEquals("HR",             response.getUserType());
    }

    @Test
    @Order(3)
    @DisplayName("TC-AUTH-03 : login(TechEvaluator) → LoginResponse inclut specialization, expertiseLevel, title")
    void login_techEvaluator_responseContainsEvaluatorFields() {
        // GIVEN
        loginRequest.setEmail("eval@test.com");
        when(authenticationManager.authenticate(any()))
            .thenReturn(new UsernamePasswordAuthenticationToken("eval@test.com", null));
        when(userRepository.findByEmail("eval@test.com"))
            .thenReturn(Optional.of(evaluatorUser));
        doNothing().when(userRepository).updateLastLogin(any(), any());
        when(jwtService.generateToken(evaluatorUser)).thenReturn("eval-token");
        when(jwtService.generateRefreshToken(evaluatorUser)).thenReturn("eval-refresh");
        when(jwtService.getJwtExpiration()).thenReturn(86400000L);

        // WHEN
        LoginResponse response = authService.login(loginRequest);

        // THEN
        assertEquals("DEVELOPER", response.getSpecialization());
        assertEquals("SENIOR",    response.getExpertiseLevel());
        assertEquals("Tech Lead", response.getTitle());
    }

    @Test
    @Order(4)
    @DisplayName("TC-AUTH-04 : login → updateLastLogin appelé exactement une fois")
    void login_validCredentials_updatesLastLogin() {
        // GIVEN
        when(authenticationManager.authenticate(any()))
            .thenReturn(new UsernamePasswordAuthenticationToken("alice@test.com", null));
        when(userRepository.findByEmail("alice@test.com"))
            .thenReturn(Optional.of(candidateUser));
        doNothing().when(userRepository).updateLastLogin(any(), any());
        when(jwtService.generateToken(any())).thenReturn("t");
        when(jwtService.generateRefreshToken(any())).thenReturn("r");
        when(jwtService.getJwtExpiration()).thenReturn(1L);

        // WHEN
        authService.login(loginRequest);

        // THEN
        verify(userRepository, times(1))
            .updateLastLogin(eq("alice@test.com"), any(java.time.LocalDateTime.class));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-01 : Login — scénarios négatifs
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("TC-AUTH-05 : login avec mauvais mot de passe → exception propagée")
    void login_badCredentials_throwsException() {
        // GIVEN
        when(authenticationManager.authenticate(any()))
            .thenThrow(new BadCredentialsException("Bad credentials"));

        // WHEN + THEN
        assertThrows(BadCredentialsException.class,
            () -> authService.login(loginRequest),
            "Une mauvaise authentification doit propager l'exception");
        // L'utilisateur ne doit pas être cherché dans le repo
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    @Order(6)
    @DisplayName("TC-AUTH-06 : login → utilisateur introuvable dans le repo → RuntimeException")
    void login_userNotFoundAfterAuth_throwsRuntimeException() {
        // GIVEN : auth réussit mais le user n'est pas en base (incohérence théorique)
        when(authenticationManager.authenticate(any()))
            .thenReturn(new UsernamePasswordAuthenticationToken("alice@test.com", null));
        when(userRepository.findByEmail("alice@test.com"))
            .thenReturn(Optional.empty());

        // WHEN + THEN
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> authService.login(loginRequest));
        assertTrue(ex.getMessage().toLowerCase().contains("not found")
            || ex.getMessage().toLowerCase().contains("user"),
            "Le message d'erreur doit mentionner l'utilisateur introuvable");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-02 : Register — scénarios positifs
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("TC-AUTH-07 : registerCandidate → retourne RegisterResponse avec tokens")
    void registerCandidate_validRequest_returnsRegisterResponseWithTokens() {
        // GIVEN
        Candidate savedCandidate = new Candidate();
        savedCandidate.setId("new-cand-001");
        savedCandidate.setFirstName("Charlie");
        savedCandidate.setLastName("Brown");
        savedCandidate.setEmail("charlie@test.com");
        savedCandidate.setPassword("$2a$encoded");
        savedCandidate.setActive(true);
        savedCandidate.setCreatedAt(LocalDateTime.now());

        when(userRepository.existsByEmail("charlie@test.com")).thenReturn(false);
        when(passwordEncoder.encode("Password123!")).thenReturn("$2a$encoded");
        when(userRepository.save(any(Candidate.class))).thenReturn(savedCandidate);
        when(jwtService.generateToken(savedCandidate)).thenReturn("new-access-token");
        when(jwtService.generateRefreshToken(savedCandidate)).thenReturn("new-refresh-token");
        when(jwtService.getJwtExpiration()).thenReturn(86400000L);

        // WHEN
        RegisterResponse response = authService.registerCandidate(registerRequest);

        // THEN
        assertNotNull(response);
        assertEquals("new-access-token",  response.getToken());
        assertEquals("new-refresh-token", response.getRefreshToken());
        assertEquals("new-cand-001",      response.getId());
        assertEquals("charlie@test.com",  response.getEmail());
        assertEquals("Charlie",           response.getFirstName());
        assertEquals("Brown",             response.getLastName());
        assertEquals("CANDIDATE",         response.getUserType());
    }

    @Test
    @Order(8)
    @DisplayName("TC-AUTH-08 : registerCandidate → le mot de passe est encodé avant sauvegarde")
    void registerCandidate_passwordIsEncodedBeforeSave() {
        // GIVEN
        Candidate saved = new Candidate();
        saved.setId("x");
        saved.setEmail("charlie@test.com");
        saved.setFirstName("Charlie");
        saved.setLastName("Brown");
        saved.setActive(true);

        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(passwordEncoder.encode("Password123!")).thenReturn("$2a$ENCODED");
        when(userRepository.save(any())).thenReturn(saved);
        when(jwtService.generateToken(any())).thenReturn("t");
        when(jwtService.generateRefreshToken(any())).thenReturn("r");
        when(jwtService.getJwtExpiration()).thenReturn(1L);

        // WHEN
        authService.registerCandidate(registerRequest);

        // THEN — vérifier que le mot de passe enregistré est l'encodé
        verify(userRepository).save(argThat(user ->
            "$2a$ENCODED".equals(user.getPassword())
        ));
    }

    @Test
    @Order(9)
    @DisplayName("TC-AUTH-09 : registerCandidate → vérifie l'unicité de l'email")
    void registerCandidate_checksEmailUniqueness() {
        // GIVEN
        Candidate saved = new Candidate();
        saved.setId("y");
        saved.setEmail("charlie@test.com");
        saved.setFirstName("Charlie");
        saved.setLastName("Brown");
        saved.setActive(true);

        when(userRepository.existsByEmail("charlie@test.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("enc");
        when(userRepository.save(any())).thenReturn(saved);
        when(jwtService.generateToken(any())).thenReturn("t");
        when(jwtService.generateRefreshToken(any())).thenReturn("r");
        when(jwtService.getJwtExpiration()).thenReturn(1L);

        // WHEN
        authService.registerCandidate(registerRequest);

        // THEN
        verify(userRepository, times(1)).existsByEmail("charlie@test.com");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // US-03 : Unicité email à l'inscription
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("TC-AUTH-10 : registerCandidate avec email déjà pris → RuntimeException")
    void registerCandidate_duplicateEmail_throwsRuntimeException() {
        // GIVEN
        when(userRepository.existsByEmail("charlie@test.com")).thenReturn(true);

        // WHEN + THEN
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> authService.registerCandidate(registerRequest));
        assertTrue(ex.getMessage().contains("charlie@test.com")
            || ex.getMessage().toLowerCase().contains("existe"),
            "Le message d'erreur doit mentionner l'email en doublon");
        // La sauvegarde ne doit jamais être appelée
        verify(userRepository, never()).save(any());
    }
}
