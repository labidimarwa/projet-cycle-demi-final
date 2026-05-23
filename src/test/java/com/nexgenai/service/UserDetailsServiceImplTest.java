package com.nexgenai.service;

import com.nexgenai.model.Candidate;
import com.nexgenai.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires de UserDetailsServiceImpl.
 *
 * Couvre loadUserByUsername :
 *   - Utilisateur actif trouvé → retourne UserDetails
 *   - Utilisateur non trouvé → UsernameNotFoundException
 *   - findActiveUserByEmail appelé (pas findByEmail)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserDetailsServiceImpl — Tests Unitaires")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserDetailsServiceImplTest {

    @Mock  private UserRepository        userRepository;
    @InjectMocks private UserDetailsServiceImpl userDetailsService;

    private Candidate activeCandidate;

    @BeforeEach
    void setUp() {
        activeCandidate = new Candidate();
        activeCandidate.setId("c-001");
        activeCandidate.setFirstName("Alice");
        activeCandidate.setLastName("Test");
        activeCandidate.setEmail("alice@test.com");
        activeCandidate.setPassword("$2a$encoded");
        activeCandidate.setActive(true);
        activeCandidate.setCreatedAt(LocalDateTime.now());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // loadUserByUsername — scénarios positifs
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-UDS-01 : loadUserByUsername(email valide) → retourne l'utilisateur actif")
    void loadUserByUsername_activeUser_returnsUserDetails() {
        // GIVEN
        when(userRepository.findActiveUserByEmail("alice@test.com"))
            .thenReturn(Optional.of(activeCandidate));

        // WHEN
        UserDetails result = userDetailsService.loadUserByUsername("alice@test.com");

        // THEN
        assertNotNull(result);
        assertEquals("alice@test.com", result.getUsername());
        assertTrue(result.isEnabled());
    }

    @Test
    @Order(2)
    @DisplayName("TC-UDS-02 : loadUserByUsername → findActiveUserByEmail appelé (et non findByEmail)")
    void loadUserByUsername_callsFindActiveUserByEmail_notFindByEmail() {
        // GIVEN
        when(userRepository.findActiveUserByEmail("alice@test.com"))
            .thenReturn(Optional.of(activeCandidate));

        // WHEN
        userDetailsService.loadUserByUsername("alice@test.com");

        // THEN
        verify(userRepository, times(1)).findActiveUserByEmail("alice@test.com");
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    @Order(3)
    @DisplayName("TC-UDS-03 : loadUserByUsername → authorities contiennent ROLE_CANDIDATE")
    void loadUserByUsername_returnsCorrectAuthorities() {
        // GIVEN
        when(userRepository.findActiveUserByEmail("alice@test.com"))
            .thenReturn(Optional.of(activeCandidate));

        // WHEN
        UserDetails result = userDetailsService.loadUserByUsername("alice@test.com");

        // THEN
        assertFalse(result.getAuthorities().isEmpty());
        assertTrue(result.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_CANDIDATE")),
            "Les authorities doivent contenir ROLE_CANDIDATE");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // loadUserByUsername — scénarios négatifs
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("TC-UDS-04 : email inexistant → UsernameNotFoundException")
    void loadUserByUsername_nonExistentEmail_throwsUsernameNotFoundException() {
        // GIVEN
        when(userRepository.findActiveUserByEmail("unknown@test.com"))
            .thenReturn(Optional.empty());

        // WHEN + THEN
        UsernameNotFoundException ex = assertThrows(
            UsernameNotFoundException.class,
            () -> userDetailsService.loadUserByUsername("unknown@test.com")
        );
        assertTrue(ex.getMessage().contains("unknown@test.com"),
            "Le message doit mentionner l'email introuvable");
    }

    @Test
    @Order(5)
    @DisplayName("TC-UDS-05 : utilisateur désactivé non trouvé → UsernameNotFoundException")
    void loadUserByUsername_inactiveUser_throwsUsernameNotFoundException() {
        // GIVEN : findActiveUserByEmail ne retourne rien (filtre isActive=true)
        when(userRepository.findActiveUserByEmail("inactive@test.com"))
            .thenReturn(Optional.empty()); // désactivé → absent de la requête

        // WHEN + THEN
        assertThrows(UsernameNotFoundException.class,
            () -> userDetailsService.loadUserByUsername("inactive@test.com"));
    }
}
