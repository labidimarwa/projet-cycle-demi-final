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

    // ── XSS patterns (split to keep complexity ≤ 20 each) ──────────────────
    private static final Pattern XSS_SCRIPT = Pattern.compile(
        "<\\s*script[^>]*>|</\\s*script\\s*>|javascript\\s*:|vbscript\\s*:|%3cscript|&#x3c;",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern XSS_EVENT = Pattern.compile(
        "on(load|error|click|mouseover|focus|blur|submit|change)\\s*=",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern XSS_EMBED = Pattern.compile(
        "<\\s*(iframe|object|embed|applet|form)[^>]*>|expression\\s*\\(|data\\s*:\\s*text/html",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // ── SQL injection patterns (split to keep complexity ≤ 20 each) ─────────
    private static final Pattern SQLI_COMMENTS = Pattern.compile(
        "'\\s*(or|and)\\s+['\"]?\\w|--(?:\\s|$|;|')",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SQLI_DDL = Pattern.compile(
        ";\\s*(drop|delete|truncate|update|insert|alter|create|exec)\\s",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SQLI_FUNCTIONS = Pattern.compile(
        "\\bunion\\b.{0,30}\\bselect\\b|\\bexec(ute)?\\s*\\(|\\bxp_\\w+|" +
        "/\\*.*\\*/|\\bsleep\\s*\\(|\\bwaitfor\\b|\\bbenchmark\\s*\\(",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // ── Path traversal ────────────────────────────────────────────────────────
    private static final Pattern PATH_TRAVERSAL = Pattern.compile(
        "\\.{2}[/\\\\]|" +       // ../  or ..\
        "\\.{2}%2f|" +           // ..%2F  (with CASE_INSENSITIVE)
        "%2e%2e|" +              // %2E%2E  (URL-encoded ..)
        "%252e",                 // %252E  (double-encoded .)
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

        if (url.contains("/actuator/health") || url.endsWith(".css") || url.endsWith(".js")) {
            chain.doFilter(request, response);
            return;
        }

        if (PATH_TRAVERSAL.matcher(url).find()) {
            eventLogger.pathTraversal(ip, method, url);
            sendError(response, 400, "Bad Request");
            return;
        }

        if (isBlockedByParams(request, response, ip, method, url)) return;

        scanHeaders(request, ip, method, url);

        addSecurityHeaders(response);
        chain.doFilter(request, response);
    }

    private boolean isBlockedByParams(HttpServletRequest request, HttpServletResponse response,
                                      String ip, String method, String url) throws IOException {
        for (Map.Entry<String, String[]> e : request.getParameterMap().entrySet()) {
            for (String val : e.getValue()) {
                AttackType type = classify(val);
                if (type != AttackType.NONE) {
                    log(type, ip, method, url, "param:" + e.getKey(), val, eventLogger);
                    if (type == AttackType.XSS || type == AttackType.SQLI || type == AttackType.CMD) {
                        sendError(response, 400, "Bad Request");
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void scanHeaders(HttpServletRequest request, String ip, String method, String url) {
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
    }

    private void addSecurityHeaders(HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-XSS-Protection", "1; mode=block");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy",
            "default-src 'self'; frame-ancestors 'none'; object-src 'none'");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private enum AttackType { NONE, XSS, SQLI, PATH, CMD }

    private AttackType classify(String value) {
        if (value == null || value.isBlank()) return AttackType.NONE;
        if (isXss(value))                          return AttackType.XSS;
        if (isSqli(value))                         return AttackType.SQLI;
        if (PATH_TRAVERSAL.matcher(value).find())  return AttackType.PATH;
        if (CMD_INJECT.matcher(value).find())      return AttackType.CMD;
        return AttackType.NONE;
    }

    private boolean isXss(String value) {
        return XSS_SCRIPT.matcher(value).find()
            || XSS_EVENT.matcher(value).find()
            || XSS_EMBED.matcher(value).find();
    }

    private boolean isSqli(String value) {
        return SQLI_COMMENTS.matcher(value).find()
            || SQLI_DDL.matcher(value).find()
            || SQLI_FUNCTIONS.matcher(value).find();
    }

    private void log(AttackType type, String ip, String method, String url,
                     String location, String payload, SecurityEventLogger logger) {
        switch (type) {
            case XSS  -> logger.xssAttempt(ip, method, url, location, payload);
            case SQLI -> logger.sqlInjectionAttempt(ip, method, url, location, payload);
            case PATH -> logger.pathTraversal(ip, method, url);
            case CMD  -> logger.xssAttempt(ip, method, url, location, "CMD:" + payload);
            default   -> { /* NONE — not an attack, no logging needed */ }
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
