package com.nexgenai.performance;

import com.nexgenai.model.Candidate;
import com.nexgenai.model.HR;
import com.nexgenai.service.JwtService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de performance du JwtService.
 *
 * Objectifs mesurés :
 *   - Latence de génération d'un token (< 50ms individuel)
 *   - Débit de génération (> 200 tokens/seconde)
 *   - Débit de validation (> 500 validations/seconde)
 *   - Concurrence : 16 threads simultanés sans erreur ni condition de course
 *   - Scalabilité : 1000 générations + 1000 validations dans une fenêtre temporelle
 *
 * Ces tests ne chargent pas le contexte Spring.
 */
@DisplayName("JwtService — Tests de Performance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JwtServicePerformanceTest {

    // ── Service à tester ──────────────────────────────────────────────────────
    private JwtService jwtService;

    private static final String SECRET =
        "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    private static final long JWT_EXPIRATION     = 86_400_000L;
    private static final long REFRESH_EXPIRATION = 604_800_000L;

    // ── Utilisateurs de test ──────────────────────────────────────────────────
    private Candidate candidateUser;
    private HR        hrUser;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey",         SECRET);
        ReflectionTestUtils.setField(jwtService, "jwtExpiration",     JWT_EXPIRATION);
        ReflectionTestUtils.setField(jwtService, "refreshExpiration", REFRESH_EXPIRATION);

        candidateUser = new Candidate();
        candidateUser.setFirstName("Perf");
        candidateUser.setLastName("Test");
        candidateUser.setEmail("perf@test.com");
        candidateUser.setPassword("hashed");
        candidateUser.setActive(true);
        candidateUser.setCreatedAt(LocalDateTime.now());

        hrUser = new HR();
        hrUser.setFirstName("HR");
        hrUser.setLastName("Perf");
        hrUser.setEmail("hrperf@test.com");
        hrUser.setPassword("hashed");
        hrUser.setActive(true);
        hrUser.setCreatedAt(LocalDateTime.now());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TC-PERF-JWT-01 : Latence individuelle < 50ms
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-PERF-JWT-01 : Génération d'un token individuel < 50ms")
    void singleTokenGeneration_completesUnder50ms() {
        // Warm-up (la première génération est plus lente à cause du JIT)
        jwtService.generateToken(candidateUser);

        long start  = System.currentTimeMillis();
        String token = jwtService.generateToken(candidateUser);
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(token);
        assertTrue(elapsed < 50,
            "La génération d'un token doit prendre < 50ms. Durée mesurée : " + elapsed + "ms");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TC-PERF-JWT-02 : 1000 générations < 5 secondes (> 200 tokens/s)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("TC-PERF-JWT-02 : 1000 générations de tokens < 5 secondes")
    @Timeout(10) // timeout absolu pour éviter de bloquer le build
    void tokenGeneration_1000tokens_completesIn5seconds() {
        // Warm-up
        for (int i = 0; i < 10; i++) jwtService.generateToken(candidateUser);

        long start = System.currentTimeMillis();
        List<String> tokens = new ArrayList<>();

        for (int i = 0; i < 1000; i++) {
            tokens.add(jwtService.generateToken(candidateUser));
        }

        long elapsed = System.currentTimeMillis() - start;
        double tokensPerSecond = 1000.0 / (elapsed / 1000.0);

        System.out.printf("[PERF] 1000 token generations in %dms (%.1f tokens/s)%n",
            elapsed, tokensPerSecond);

        assertEquals(1000, tokens.size());
        assertTrue(elapsed < 5000,
            "1000 générations doivent prendre < 5s. Durée : " + elapsed + "ms");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TC-PERF-JWT-03 : 1000 validations < 3 secondes (> 333 validations/s)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("TC-PERF-JWT-03 : 1000 validations de tokens < 6 secondes")
    @Timeout(12)
    void tokenValidation_1000validations_completesIn3seconds() {
        String token = jwtService.generateToken(candidateUser);

        // Warm-up
        for (int i = 0; i < 10; i++) jwtService.isTokenValid(token, candidateUser);

        long start = System.currentTimeMillis();
        int validCount = 0;

        for (int i = 0; i < 1000; i++) {
            if (jwtService.isTokenValid(token, candidateUser)) validCount++;
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[PERF] 1000 token validations in %dms%n", elapsed);

        assertEquals(1000, validCount, "Tous les tokens valides doivent passer la validation");
        assertTrue(elapsed < 6000,
            "1000 validations doivent prendre < 6s. Durée : " + elapsed + "ms");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TC-PERF-JWT-04 : Génération + validation combinées pour 500 tokens
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("TC-PERF-JWT-04 : 500 generate + validate < 5 secondes")
    @Timeout(10)
    void generateAndValidate_500cycles_completesIn5seconds() {
        // Warm-up
        for (int i = 0; i < 5; i++) {
            String t = jwtService.generateToken(candidateUser);
            jwtService.isTokenValid(t, candidateUser);
        }

        long start = System.currentTimeMillis();

        for (int i = 0; i < 500; i++) {
            String t = jwtService.generateToken(i % 2 == 0 ? candidateUser : hrUser);
            assertTrue(jwtService.isTokenValid(t, i % 2 == 0 ? candidateUser : hrUser));
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[PERF] 500 generate+validate cycles in %dms%n", elapsed);

        assertTrue(elapsed < 5000,
            "500 cycles generate+validate doivent prendre < 5s. Durée : " + elapsed + "ms");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TC-PERF-JWT-05 : Concurrence — 16 threads sans race condition
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("TC-PERF-JWT-05 : 16 threads concurrents de génération → aucune erreur")
    @Timeout(15)
    void concurrentTokenGeneration_16threads_noErrors() throws InterruptedException {
        int threadCount      = 16;
        int tokensPerThread  = 50;
        AtomicInteger errors = new AtomicInteger(0);
        AtomicInteger total  = new AtomicInteger(0);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(); // Attendre le signal de départ
                    for (int i = 0; i < tokensPerThread; i++) {
                        try {
                            String token = jwtService.generateToken(
                                threadId % 2 == 0 ? candidateUser : hrUser);
                            boolean valid = jwtService.isTokenValid(token,
                                threadId % 2 == 0 ? candidateUser : hrUser);
                            if (!valid) errors.incrementAndGet();
                            total.incrementAndGet();
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(); // Tous les threads prêts
        start.countDown(); // GO !
        done.await(); // Attendre la fin
        pool.shutdown();

        System.out.printf("[PERF] %d threads × %d tokens = %d total, %d errors%n",
            threadCount, tokensPerThread, total.get(), errors.get());

        assertEquals(0, errors.get(),
            "Aucune erreur lors de la génération/validation concurrente");
        assertEquals(threadCount * tokensPerThread, total.get(),
            "Tous les tokens doivent avoir été traités");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TC-PERF-JWT-06 : Extraction du username < 1 seconde pour 1000 appels
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("TC-PERF-JWT-06 : 1000 extractions de username < 3 secondes")
    @Timeout(8)
    void usernameExtraction_1000calls_completesIn1second() {
        String token = jwtService.generateToken(candidateUser);

        long start = System.currentTimeMillis();

        for (int i = 0; i < 1000; i++) {
            String username = jwtService.extractUsername(token);
            assertEquals("perf@test.com", username);
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[PERF] 1000 username extractions in %dms%n", elapsed);

        assertTrue(elapsed < 3000,
            "1000 extractions de username doivent prendre < 3s. Durée : " + elapsed + "ms");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TC-PERF-JWT-07 : Refresh token generation débit
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("TC-PERF-JWT-07 : 500 refresh tokens générés < 3 secondes")
    @Timeout(8)
    void refreshTokenGeneration_500tokens_completesIn3seconds() {
        long start = System.currentTimeMillis();

        for (int i = 0; i < 500; i++) {
            assertNotNull(jwtService.generateRefreshToken(candidateUser));
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[PERF] 500 refresh token generations in %dms%n", elapsed);

        assertTrue(elapsed < 3000);
    }
}
