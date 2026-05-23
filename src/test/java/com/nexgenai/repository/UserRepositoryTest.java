package com.nexgenai.repository;

import com.nexgenai.model.Candidate;
import com.nexgenai.model.HR;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests d'intégration du UserRepository avec H2 en mémoire.
 *
 * Vérifie les requêtes custom :
 *   - findByEmail
 *   - existsByEmail
 *   - findActiveUserByEmail (filtre isActive=true)
 *   - updateLastLogin (requête @Modifying)
 *   - searchUsers (LIKE sur firstName, lastName, email)
 *
 * Utilise @DataJpaTest qui configure automatiquement le schéma JPA.
 * @AutoConfigureTestDatabase(replace=NONE) utilise la datasource H2 de
 * application-test.properties (avec MODE=MySQL, NON_KEYWORDS=USER).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("UserRepository — Tests d'Intégration DataJPA")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Candidate createAndSaveCandidate(String email, boolean active) {
        Candidate c = new Candidate();
        c.setFirstName("Test");
        c.setLastName("User");
        c.setEmail(email);
        c.setPassword("$2a$encoded");
        c.setActive(active);
        c.setCreatedAt(LocalDateTime.now());
        return userRepository.save(c);
    }

    private HR createAndSaveHR(String email, String firstName, String lastName) {
        HR hr = new HR();
        hr.setFirstName(firstName);
        hr.setLastName(lastName);
        hr.setEmail(email);
        hr.setPassword("$2a$encoded");
        hr.setActive(true);
        hr.setDepartment("IT");
        hr.setCreatedAt(LocalDateTime.now());
        return (HR) userRepository.save(hr);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findByEmail
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-UREPO-01 : findByEmail(email existant) → retourne l'utilisateur")
    void findByEmail_existingEmail_returnsUser() {
        // GIVEN
        createAndSaveCandidate("alice@repo.test", true);

        // WHEN
        Optional<com.nexgenai.model.User> result = userRepository.findByEmail("alice@repo.test");

        // THEN
        assertTrue(result.isPresent(), "L'utilisateur doit être trouvé");
        assertEquals("alice@repo.test", result.get().getEmail());
    }

    @Test
    @Order(2)
    @DisplayName("TC-UREPO-02 : findByEmail(email inexistant) → Optional.empty()")
    void findByEmail_nonExistingEmail_returnsEmpty() {
        Optional<com.nexgenai.model.User> result = userRepository.findByEmail("ghost@test.com");
        assertTrue(result.isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // existsByEmail
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("TC-UREPO-03 : existsByEmail(email existant) → true")
    void existsByEmail_existingEmail_returnsTrue() {
        createAndSaveCandidate("bob@repo.test", true);
        assertTrue(userRepository.existsByEmail("bob@repo.test"));
    }

    @Test
    @Order(4)
    @DisplayName("TC-UREPO-04 : existsByEmail(email inexistant) → false")
    void existsByEmail_nonExistingEmail_returnsFalse() {
        assertFalse(userRepository.existsByEmail("nobody@test.com"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findActiveUserByEmail — filtre isActive=true
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("TC-UREPO-05 : findActiveUserByEmail(utilisateur actif) → retourne l'utilisateur")
    void findActiveUserByEmail_activeUser_returnsUser() {
        // GIVEN
        createAndSaveCandidate("active@repo.test", true);

        // WHEN
        Optional<com.nexgenai.model.User> result =
            userRepository.findActiveUserByEmail("active@repo.test");

        // THEN
        assertTrue(result.isPresent());
        assertEquals("active@repo.test", result.get().getEmail());
        assertTrue(result.get().isActive());
    }

    @Test
    @Order(6)
    @DisplayName("TC-UREPO-06 : findActiveUserByEmail(utilisateur inactif) → Optional.empty()")
    void findActiveUserByEmail_inactiveUser_returnsEmpty() {
        // GIVEN : utilisateur créé avec isActive=false
        createAndSaveCandidate("inactive@repo.test", false);

        // WHEN : la requête filtre WHERE isActive=true
        Optional<com.nexgenai.model.User> result =
            userRepository.findActiveUserByEmail("inactive@repo.test");

        // THEN
        assertTrue(result.isEmpty(),
            "Un utilisateur inactif ne doit pas être retourné par findActiveUserByEmail");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // updateLastLogin
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("TC-UREPO-07 : updateLastLogin → met à jour la date de dernière connexion")
    void updateLastLogin_setsCorrectTimestamp() {
        // GIVEN
        createAndSaveCandidate("login@repo.test", true);
        LocalDateTime loginTime = LocalDateTime.of(2024, 6, 15, 10, 30, 0);

        // WHEN
        userRepository.updateLastLogin("login@repo.test", loginTime);
        entityManager.clear(); // flush L1 cache so the next query hits the DB

        // THEN
        Optional<com.nexgenai.model.User> user =
            userRepository.findByEmail("login@repo.test");
        assertTrue(user.isPresent());
        assertNotNull(user.get().getLastLogin());
        assertEquals(loginTime, user.get().getLastLogin());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // searchUsers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(8)
    @DisplayName("TC-UREPO-08 : searchUsers('alice') → trouve par prénom")
    void searchUsers_byFirstName_returnsMatchingUsers() {
        // GIVEN
        createAndSaveHR("hr.alice@repo.test", "Alice", "Wonder");
        createAndSaveHR("hr.bob@repo.test",   "Bob",   "Smith");

        // WHEN
        Page<com.nexgenai.model.User> result =
            userRepository.searchUsers("alice", PageRequest.of(0, 10));

        // THEN
        assertEquals(1, result.getTotalElements());
        assertEquals("Alice", result.getContent().get(0).getFirstName());
    }

    @Test
    @Order(9)
    @DisplayName("TC-UREPO-09 : searchUsers('Wonder') → trouve par nom de famille")
    void searchUsers_byLastName_returnsMatchingUsers() {
        // GIVEN (Alice Wonder déjà créée dans le test précédent ou créée ici)
        createAndSaveHR("hr.wonder@repo.test", "Alice", "Wonder");

        Page<com.nexgenai.model.User> result =
            userRepository.searchUsers("Wonder", PageRequest.of(0, 10));

        assertFalse(result.isEmpty());
        assertTrue(result.getContent().stream()
            .anyMatch(u -> "Wonder".equals(u.getLastName())));
    }

    @Test
    @Order(10)
    @DisplayName("TC-UREPO-10 : searchUsers('hr.alice') → trouve par email")
    void searchUsers_byEmail_returnsMatchingUsers() {
        // GIVEN
        createAndSaveHR("hr.alice.search@repo.test", "TestFirst", "TestLast");

        Page<com.nexgenai.model.User> result =
            userRepository.searchUsers("alice.search", PageRequest.of(0, 10));

        assertFalse(result.isEmpty());
        assertTrue(result.getContent().stream()
            .anyMatch(u -> u.getEmail().contains("alice.search")));
    }

    @Test
    @Order(11)
    @DisplayName("TC-UREPO-11 : searchUsers(terme inexistant) → page vide")
    void searchUsers_noMatch_returnsEmptyPage() {
        Page<com.nexgenai.model.User> result =
            userRepository.searchUsers("zzz_no_match_xyz_987", PageRequest.of(0, 10));
        assertEquals(0, result.getTotalElements());
    }
}
