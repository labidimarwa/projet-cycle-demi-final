package com.nexgenai.security;

import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests de performance des filtres de sécurité.
 *
 * Objectifs :
 *   - SecurityAuditFilter doit traiter 500 requêtes propres en < 2 secondes
 *   - SecurityAuditFilter doit détecter les payloads XSS/SQLi en < 10ms par requête
 *   - RateLimitingFilter doit gérer le comptage concurrent sans erreur
 *   - Les filtres ne doivent pas introduire de race conditions sous charge
 *
 * Approche : tests unitaires avec MockHttpServletRequest (pas de Spring context).
 * Placé dans le même package que SecurityAuditFilter pour accéder à doFilterInternal (protected).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Security Filters — Tests de Performance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityFiltersPerformanceTest {

    @Mock private SecurityEventLogger eventLogger;

    private SecurityAuditFilter auditFilter;

    @BeforeEach
    void setUp() {
        auditFilter = new SecurityAuditFilter(eventLogger);
    }

    private MockHttpServletRequest cleanRequest(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI(uri);
        req.setRemoteAddr("127.0.0.1");
        req.setMethod("GET");
        return req;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TC-PERF-SEC-01 : 500 requêtes propres traitées en < 2 secondes
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TC-PERF-SEC-01 : SecurityAuditFilter — 500 requêtes propres < 2 secondes")
    @Timeout(5)
    void auditFilter_500cleanRequests_completesIn2Seconds() throws Exception {
        long start = System.currentTimeMillis();

        for (int i = 0; i < 500; i++) {
            MockHttpServletRequest  req   = cleanRequest("/api/v1/jobs");
            MockHttpServletResponse res   = new MockHttpServletResponse();
            MockFilterChain         chain = new MockFilterChain();

            auditFilter.doFilterInternal(req, res, chain);

            assertEquals(200, res.getStatus());  // pas de blocage
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[PERF] 500 clean requests processed in %dms%n", elapsed);

        assertTrue(elapsed < 2000,
            "500 requêtes propres doivent être traitées en < 2s. Durée : " + elapsed + "ms");
        // Aucun événement de sécurité ne doit être loggé
        verifyNoInteractions(eventLogger);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TC-PERF-SEC-02 : Détection XSS sur 100 requêtes malveillantes < 1 seconde
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("TC-PERF-SEC-02 : Détection XSS sur 100 payloads < 1 seconde")
    @Timeout(5)
    void auditFilter_100xssPayloads_detectedUnder1Second() throws Exception {
        doNothing().when(eventLogger).xssAttempt(any(), any(), any(), any(), any());

        long start = System.currentTimeMillis();

        for (int i = 0; i < 100; i++) {
            MockHttpServletRequest  req   = cleanRequest("/api/v1/jobs");
            MockHttpServletResponse res   = new MockHttpServletResponse();
            MockFilterChain         chain = new MockFilterChain();
            req.addParameter("q", "<script>alert(" + i + ")</script>");

            auditFilter.doFilterInternal(req, res, chain);

            assertEquals(400, res.getStatus(), "XSS doit être bloqué avec 400");
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[PERF] 100 XSS detections in %dms%n", elapsed);

        assertTrue(elapsed < 1000,
            "100 détections XSS doivent prendre < 1s. Durée : " + elapsed + "ms");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TC-PERF-SEC-03 : Détection Path Traversal sur 100 URIs < 500ms
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("TC-PERF-SEC-03 : Détection Path Traversal sur 100 URIs < 500ms")
    @Timeout(3)
    void auditFilter_100pathTraversalAttacks_detectedUnder500ms() throws Exception {
        doNothing().when(eventLogger).pathTraversal(any(), any(), any());

        long start = System.currentTimeMillis();

        for (int i = 0; i < 100; i++) {
            MockHttpServletRequest  req   = cleanRequest("/api/v1/../etc/passwd");
            MockHttpServletResponse res   = new MockHttpServletResponse();
            MockFilterChain         chain = new MockFilterChain();

            auditFilter.doFilterInternal(req, res, chain);

            assertEquals(400, res.getStatus());
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[PERF] 100 path traversal detections in %dms%n", elapsed);

        assertTrue(elapsed < 500,
            "100 détections Path Traversal doivent prendre < 500ms. Durée : " + elapsed + "ms");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TC-PERF-SEC-04 : Sécurité des headers sur 500 requêtes < 2 secondes
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("TC-PERF-SEC-04 : Ajout des headers de sécurité sur 500 requêtes < 2 secondes")
    @Timeout(5)
    void auditFilter_500requests_securityHeadersAddedPerformantly() throws Exception {
        long start = System.currentTimeMillis();

        for (int i = 0; i < 500; i++) {
            MockHttpServletRequest  req   = cleanRequest("/api/v1/jobs");
            MockHttpServletResponse res   = new MockHttpServletResponse();
            MockFilterChain         chain = new MockFilterChain();

            auditFilter.doFilterInternal(req, res, chain);

            // Vérifier que les headers sont présents (chaque 50ème)
            if (i % 50 == 0) {
                assertEquals("nosniff", res.getHeader("X-Content-Type-Options"));
                assertEquals("DENY",    res.getHeader("X-Frame-Options"));
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[PERF] 500 security header injections in %dms%n", elapsed);

        assertTrue(elapsed < 2000);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TC-PERF-SEC-05 : Concurrence — 8 threads d'audit simultanés sans erreur
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("TC-PERF-SEC-05 : 8 threads d'audit simultanés → pas de race condition")
    @Timeout(10)
    void auditFilter_8ConcurrentThreads_noRaceConditions() throws InterruptedException {
        int threads           = 8;
        int requestsPerThread = 50;
        AtomicInteger errors  = new AtomicInteger(0);
        CountDownLatch start  = new CountDownLatch(1);
        CountDownLatch done   = new CountDownLatch(threads);
        ExecutorService pool  = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < requestsPerThread; i++) {
                        MockHttpServletRequest  req   = cleanRequest("/api/v1/jobs");
                        MockHttpServletResponse res   = new MockHttpServletResponse();
                        MockFilterChain         chain = new MockFilterChain();
                        req.addParameter("status", "ACTIVE");

                        auditFilter.doFilterInternal(req, res, chain);

                        if (res.getStatus() != 200) errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        pool.shutdown();

        System.out.printf("[PERF] %d threads × %d requests = %d total, %d errors%n",
            threads, requestsPerThread, threads * requestsPerThread, errors.get());

        assertEquals(0, errors.get(),
            "Aucune erreur lors de l'audit concurrent");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TC-PERF-SEC-06 : Actuator health bypass est rapide
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("TC-PERF-SEC-06 : Bypass health endpoint (1000 requêtes) < 1 seconde")
    @Timeout(5)
    void auditFilter_1000healthChecks_bypassedFastly() throws Exception {
        long start = System.currentTimeMillis();

        for (int i = 0; i < 1000; i++) {
            MockHttpServletRequest  req   = cleanRequest("/actuator/health");
            MockHttpServletResponse res   = new MockHttpServletResponse();
            MockFilterChain         chain = new MockFilterChain();

            auditFilter.doFilterInternal(req, res, chain);

            // Health endpoint est bypassé → chain proceed, pas de headers
            assertNotNull(chain.getRequest());
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[PERF] 1000 health checks bypassed in %dms%n", elapsed);

        assertTrue(elapsed < 1000,
            "1000 health checks doivent être bypassés en < 1s. Durée : " + elapsed + "ms");
        verifyNoInteractions(eventLogger);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TC-PERF-SEC-07 : Scan de multiples paramètres en même temps
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("TC-PERF-SEC-07 : 200 requêtes avec 10 paramètres chacune < 2 secondes")
    @Timeout(5)
    void auditFilter_200requestsWith10params_completesIn2Seconds() throws Exception {
        long start = System.currentTimeMillis();

        for (int i = 0; i < 200; i++) {
            MockHttpServletRequest  req   = cleanRequest("/api/v1/jobs");
            MockHttpServletResponse res   = new MockHttpServletResponse();
            MockFilterChain         chain = new MockFilterChain();

            // Ajouter 10 paramètres propres
            for (int p = 0; p < 10; p++) {
                req.addParameter("param" + p, "value" + p + "_" + i);
            }

            auditFilter.doFilterInternal(req, res, chain);
            assertEquals(200, res.getStatus());
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[PERF] 200 requests with 10 params each in %dms%n", elapsed);

        assertTrue(elapsed < 2000,
            "200 requêtes × 10 params doivent être traitées en < 2s. Durée : " + elapsed + "ms");
    }
}
