package com.nexgenai.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * OWASP-aware audit filter — runs on every request before the JWT filter.
 * Detects XSS payloads, SQL injection patterns, and path traversal attempts,
 * logs them to security-audit.log, and blocks the most dangerous patterns
 * with HTTP 400.
 *
 * Covers: OWASP A01, A03, A05, A08
 */
@Component
@Order(1)   // before JWT filter (order 2)
@RequiredArgsConstructor
public class SecurityAuditFilter extends OncePerRequestFilter {

    private final SecurityEventLogger eventLogger;

    // ── XSS patterns ─────────────────────────────────────────────────────────
    private static final Pattern XSS = Pattern.compile(
        "<\\s*script[^>]*>|</\\s*script\\s*>|" +
        "javascript\\s*:|vbscript\\s*:|" +
        "on(load|error|click|mouseover|focus|blur|submit|change)\\s*=|" +
        "<\\s*(iframe|object|embed|applet|form)[^>]*>|" +
        "expression\\s*\\(|" +
        "data\\s*:\\s*text/html|" +
        "%3[Cc]script|&#x3[Cc];",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // ── SQL injection patterns ────────────────────────────────────────────────
    private static final Pattern SQLI = Pattern.compile(
        "'\\s*(or|and)\\s+['\"]?\\w|" +
        "--(?:\\s|$|;|')|" +     // SQL comment: --, -- , --; or --' (no trailing-space requirement)
        ";\\s*(drop|delete|truncate|update|insert|alter|create|exec)\\s|" +
        "\\bunion\\b.{0,30}\\bselect\\b|" +
        "\\bexec(ute)?\\s*\\(|" +
        "\\bxp_\\w+|" +
        "/\\*.*\\*/|" +
        "\\bsleep\\s*\\(|\\bwaitfor\\b|\\bbenchmark\\s*\\(",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // ── Path traversal ────────────────────────────────────────────────────────
    private static final Pattern PATH_TRAVERSAL = Pattern.compile(
        "\\.{2}[/\\\\]|" +       // ../  or ..\
        "\\.{2}%2[Ff]|" +        // ..%2F  or ..%2f
        "%2[Ee]%2[Ee]|" +        // %2E%2E  (URL-encoded ..)
        "%252[Ee]",               // %252E  (double-encoded .)
        Pattern.CASE_INSENSITIVE
    );

    // ── Command injection ─────────────────────────────────────────────────────
    private static final Pattern CMD_INJECT = Pattern.compile(
        "[;&|`$]\\s*(ls|cat|rm|mv|cp|wget|curl|bash|sh|python|perl|ruby|nc|ncat)\\b",
        Pattern.CASE_INSENSITIVE
    );

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String ip     = getClientIp(request);
        String method = request.getMethod();
        String url    = request.getRequestURI();

        // Skip static resources and actuator health
        if (url.contains("/actuator/health") || url.endsWith(".css") || url.endsWith(".js")) {
            chain.doFilter(request, response);
            return;
        }

        // 1. Check URI for path traversal
        if (PATH_TRAVERSAL.matcher(url).find()) {
            eventLogger.pathTraversal(ip, method, url);
            sendError(response, 400, "Bad Request");
            return;
        }

        // 2. Scan query parameters
        for (Map.Entry<String, String[]> e : request.getParameterMap().entrySet()) {
            for (String val : e.getValue()) {
                AttackType type = classify(val);
                if (type != AttackType.NONE) {
                    log(type, ip, method, url, "param:" + e.getKey(), val, eventLogger);
                    if (type == AttackType.XSS || type == AttackType.CMD) {
                        sendError(response, 400, "Bad Request");
                        return;
                    }
                }
            }
        }

        // 3. Scan selected request headers (not all — avoid false positives on Authorization)
        String[] headersToCheck = {"Referer", "X-Forwarded-For", "User-Agent", "X-Custom-Header"};
        for (String h : headersToCheck) {
            String v = request.getHeader(h);
            if (v != null) {
                AttackType type = classify(v);
                if (type != AttackType.NONE) {
                    log(type, ip, method, url, "header:" + h, v, eventLogger);
                    // Log only; don't block on User-Agent — scanners send probes
                }
            }
        }

        // 4. Add security response headers (OWASP Secure Headers)
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-XSS-Protection", "1; mode=block");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Cache-Control", "no-store");

        chain.doFilter(request, response);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private enum AttackType { NONE, XSS, SQLI, PATH, CMD }

    private AttackType classify(String value) {
        if (value == null || value.isBlank()) return AttackType.NONE;
        if (XSS.matcher(value).find())             return AttackType.XSS;
        if (SQLI.matcher(value).find())            return AttackType.SQLI;
        if (PATH_TRAVERSAL.matcher(value).find())  return AttackType.PATH;
        if (CMD_INJECT.matcher(value).find())      return AttackType.CMD;
        return AttackType.NONE;
    }

    private void log(AttackType type, String ip, String method, String url,
                     String location, String payload, SecurityEventLogger logger) {
        switch (type) {
            case XSS  -> logger.xssAttempt(ip, method, url, location, payload);
            case SQLI -> logger.sqlInjectionAttempt(ip, method, url, location, payload);
            case PATH -> logger.pathTraversal(ip, method, url);
            case CMD  -> logger.xssAttempt(ip, method, url, location, "CMD:" + payload);
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private void sendError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
