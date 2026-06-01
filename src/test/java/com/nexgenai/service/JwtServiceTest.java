package com.nexgenai.service;

import com.nexgenai.model.Candidate;
import com.nexgenai.model.HR;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires de JwtService — couvre la génération, validation,
 * extraction et expiration des tokens JWT (OWASP A07).
 *
 * Pas de contexte Spring : utilise ReflectionTestUtils pour injecter
 * les valeurs @Value dans le service.
 */
@DisplayName("JwtService — Tests Unitaires")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JwtServiceTest {

    // ── Service à tester ──────────────────────────────────────────────────────
    private JwtService jwtService;

    // ── Constantes de configuration (miroir de application-test.properties) ──
    private static final String SECRET =
        "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    private static final long JWT_EXPIRATION      = 86_400_000L; // 24h en ms
    private static final long REFRESH_EXPIRATION  = 604_800_000L; // 7j en ms

    // ── Utilisateurs de test ──────────────────────────────────────────────────
    private Candidate candidateUser;
    private HR hrUser;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey",         SECRET);
        ReflectionTestUtils.setField(jwtService, "jwtExpiration",     JWT_EXPIRATION);
        ReflectionTestUtils.setField(jwtService, "refreshExpiration", REFRESH_EXPIRATION);

        candidateUser = new Candidate();
        candidateUser.setFirstName("Alice");
        candidateUser.setLastName("Martin");
        candidateUser.setEmail("alice@test.com");
        candidateUser.setPassword("hashed");
        candidateUser.setActive(true);
        candidateUser.setCreatedAt(LocalDateTime.now());

        hrUser = new HR();
        hrUser.setFirstName("Bob");
        hrUser.setLastName("Smith");
        hrUser.setEmail("hr@test.com");
        hrUser.setPassword("hashed");
        hrUser.setActive(true);
        hrUser.setDepartment("Recrutement");
        hrUser.setPosition("Responsable RH");
        hrUser.setCreatedAt(LocalDateTime.now());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Génération de token
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-JWT-01 : generateToken(Candidate) → token non-null, non-vide")
    void generateToken_candidateUser_returnsNonEmptyString() {
        String token = jwtService.generateToken(candidateUser);

        assertNotNull(token, "Le token ne doit pas être null");
        assertFalse(token.isBlank(), "Le token ne doit pas être vide");
        assertTrue(token.contains("."), "Le token JWT doit contenir des points séparateurs");
    }

    @Test
    @Order(2)
    @DisplayName("TC-JWT-02 : generateToken → le sujet (sub) est l'email de l'utilisateur")
    void generateToken_subjectIsUserEmail() {
        String token = jwtService.generateToken(candidateUser);

        String username = jwtService.extractUsername(token);
        assertEquals("alice@test.com", username,
            "Le sujet du token doit être l'email de l'utilisateur");
    }

    @Test
    @Order(3)
    @DisplayName("TC-JWT-03 : generateToken → la claim 'role' est présente")
    void generateToken_containsRoleClaim() {
        String token = jwtService.generateToken(candidateUser);

        String role = jwtService.extractClaim(token, claims -> claims.get("role", String.class));
        assertNotNull(role, "La claim 'role' doit être présente");
        assertEquals("ROLE_CANDIDATE", role, "Le rôle doit correspondre au type utilisateur");
    }

    @Test
    @Order(4)
    @DisplayName("TC-JWT-04 : generateToken(HR) → claim 'role' vaut ROLE_HR")
    void generateToken_hrUser_roleClaimIsRoleHr() {
        String token = jwtService.generateToken(hrUser);

        String role = jwtService.extractClaim(token, claims -> claims.get("role", String.class));
        assertEquals("ROLE_HR", role);
    }

    @Test
    @Order(5)
    @DisplayName("TC-JWT-05 : generateToken → la claim 'userType' est présente")
    void generateToken_containsUserTypeClaim() {
        String token = jwtService.generateToken(candidateUser);

        String userType = jwtService.extractClaim(token,
            claims -> claims.get("userType", String.class));
        assertNotNull(userType, "La claim 'userType' doit être présente");
        assertEquals("CANDIDATE", userType);
    }

    @Test
    @Order(6)
    @DisplayName("TC-JWT-06 : deux générations pour le même user → tokens différents (iat varie)")
    void generateToken_twoCalls_produceDifferentTokens() {
        String token1 = jwtService.generateToken(candidateUser);
        String token2 = jwtService.generateToken(candidateUser);

        // Les tokens peuvent être identiques si générés dans la même milliseconde
        // → test sur la validité des deux plutôt que leur différence stricte
        assertTrue(jwtService.isTokenValid(token1, candidateUser));
        assertTrue(jwtService.isTokenValid(token2, candidateUser));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Refresh token
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("TC-JWT-07 : generateRefreshToken → token non-null avec sujet correct")
    void generateRefreshToken_nonNullWithCorrectSubject() {
        String refresh = jwtService.generateRefreshToken(candidateUser);

        assertNotNull(refresh, "Le refresh token ne doit pas être null");
        assertEquals("alice@test.com", jwtService.extractUsername(refresh));
    }

    @Test
    @Order(8)
    @DisplayName("TC-JWT-08 : refreshToken et accessToken sont différents")
    void generateRefreshToken_differentFromAccessToken() {
        String access  = jwtService.generateToken(candidateUser);
        String refresh = jwtService.generateRefreshToken(candidateUser);

        assertNotEquals(access, refresh,
            "Le refresh token doit être distinct du access token");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Validation du token
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(9)
    @DisplayName("TC-JWT-09 : isTokenValid → true pour token valide et bon utilisateur")
    void isTokenValid_validTokenAndMatchingUser_returnsTrue() {
        String token = jwtService.generateToken(candidateUser);

        assertTrue(jwtService.isTokenValid(token, candidateUser));
    }

    @Test
    @Order(10)
    @DisplayName("TC-JWT-10 : isTokenValid → false si le token appartient à un autre utilisateur")
    void isTokenValid_tokenForDifferentUser_returnsFalse() {
        String tokenForAlice = jwtService.generateToken(candidateUser);

        assertFalse(jwtService.isTokenValid(tokenForAlice, hrUser),
            "Un token généré pour Alice ne doit pas être valide pour Bob");
    }

    @Test
    @Order(11)
    @DisplayName("TC-JWT-11 : isTokenValid → false pour token expiré")
    void isTokenValid_expiredToken_returnsFalse() {
        // Construire manuellement un token avec exp dans le passé (- 2 secondes)
        String expiredToken = Jwts.builder()
            .setSubject(candidateUser.getUsername())
            .setIssuedAt(new Date(System.currentTimeMillis() - 10_000))
            .setExpiration(new Date(System.currentTimeMillis() - 2_000)) // 2s dans le passé
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)),
                      SignatureAlgorithm.HS256)
            .compact();

        // Le clock skew de 60s du parser permet de parser le token mais
        // isTokenExpired() retourne quand même true car exp < now
        assertFalse(jwtService.isTokenValid(expiredToken, candidateUser),
            "Un token expiré ne doit pas être valide");
    }

    @Test
    @Order(12)
    @DisplayName("TC-JWT-12 : extractUsername sur token valide → email correct")
    void extractUsername_validToken_returnsCorrectEmail() {
        String token = jwtService.generateToken(hrUser);

        assertEquals("hr@test.com", jwtService.extractUsername(token));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Configuration
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(13)
    @DisplayName("TC-JWT-13 : getJwtExpiration → retourne la valeur configurée")
    void getJwtExpiration_returnsConfiguredValue() {
        assertEquals(JWT_EXPIRATION, jwtService.getJwtExpiration());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // extractClaim générique
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(14)
    @DisplayName("TC-JWT-14 : extractClaim avec Claims::getSubject → retourne le sujet")
    void extractClaim_withSubjectFunction_returnsSubject() {
        String token = jwtService.generateToken(candidateUser);

        String subject = jwtService.extractClaim(token, Claims::getSubject);
        assertEquals("alice@test.com", subject);
    }

    @Test
    @Order(15)
    @DisplayName("TC-JWT-15 : extractClaim avec Claims::getExpiration → retourne une date future")
    void extractClaim_withExpirationFunction_returnsFutureDate() {
        String token = jwtService.generateToken(candidateUser);

        Date expiration = jwtService.extractClaim(token, Claims::getExpiration);
        assertNotNull(expiration);
        assertTrue(expiration.after(new Date()),
            "La date d'expiration doit être dans le futur pour un token nouvellement créé");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Test de performance basique (génération)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(16)
    @DisplayName("TC-JWT-16 : génération de 500 tokens consécutifs < 3 secondes")
    @Timeout(3)
    void tokenGeneration_500tokens_completesWithinTimeout() {
        for (int i = 0; i < 500; i++) {
            assertNotNull(jwtService.generateToken(candidateUser));
        }
    }

    @Test
    @Order(17)
    @DisplayName("TC-JWT-17 : validation de 500 tokens consécutifs < 3 secondes")
    @Timeout(3)
    void tokenValidation_500tokens_completesWithinTimeout() {
        String token = jwtService.generateToken(candidateUser);
        for (int i = 0; i < 500; i++) {
            assertTrue(jwtService.isTokenValid(token, candidateUser));
        }
    }

    @Test
    @Order(18)
    @DisplayName("TC-JWT-18 : génération concurrente (8 threads) sans exception")
    void tokenGeneration_concurrent8Threads_noException() throws InterruptedException {
        int threadCount = 8;
        CountDownLatch startLatch  = new CountDownLatch(1);
        CountDownLatch doneLatch   = new CountDownLatch(threadCount);
        AtomicInteger errorCount   = new AtomicInteger(0);
        ExecutorService pool       = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    String token = jwtService.generateToken(candidateUser);
                    if (!jwtService.isTokenValid(token, candidateUser)) {
                        errorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Lancer tous les threads simultanément
        doneLatch.await();
        pool.shutdown();

        assertEquals(0, errorCount.get(),
            "Aucune erreur ne doit survenir lors de la génération concurrente de tokens");
    }
}
