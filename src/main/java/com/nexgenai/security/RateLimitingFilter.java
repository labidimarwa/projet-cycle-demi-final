package com.nexgenai.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IP-based sliding-window rate limiting filter (OWASP A04 — Insecure Design / DoS).
 * Order 2 — runs after SecurityAuditFilter but before JWT filter.
 *
 * Limits:
 *   /auth/**         → 10 requests / 60 s  (brute-force protection)
 *   everything else  → 200 requests / 60 s (general DoS protection)
 */
@Component
@Order(2)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitingFilter extends OncePerRequestFilter {

    private final SecurityEventLogger eventLogger;

    private static final int  WINDOW_SECONDS   = 60;
    private static final int  AUTH_MAX          = 10;
    private static final int  GENERAL_MAX       = 200;

    // key = "ip:windowStart(epoch-seconds / WINDOW)"
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>          windows  = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String ip      = getClientIp(request);
        String url     = request.getRequestURI();
        boolean isAuth = url.startsWith("/auth/");
        int limit      = isAuth ? AUTH_MAX : GENERAL_MAX;

        String key     = ip + "|" + (isAuth ? "auth" : "gen");
        long   nowSlot = Instant.now().getEpochSecond() / WINDOW_SECONDS;

        // Reset counter when the window has rolled
        windows.compute(key, (k, prev) -> {
            if (prev == null || prev != nowSlot) {
                counters.put(k, new AtomicInteger(0));
                return nowSlot;
            }
            return prev;
        });

        int count = counters.computeIfAbsent(key, k -> new AtomicInteger(0))
                            .incrementAndGet();

        if (count > limit) {
            eventLogger.rateLimitExceeded(ip, url, count);
            if (isAuth) {
                eventLogger.bruteForce(ip, "unknown");
            }
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", String.valueOf(WINDOW_SECONDS));
            response.getWriter().write("{\"error\":\"Too Many Requests\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
