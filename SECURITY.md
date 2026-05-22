# NexgenAI — Security Documentation

## Overview

This document describes the security controls implemented in the NexgenAI backend.
Every control maps to a specific OWASP Top 10 category.

---

## 1. Architecture — Security Layers

```
HTTP Request
     │
     ▼
┌─────────────────────────────┐
│  SecurityAuditFilter        │  @Order(1) — XSS, SQLI, Path Traversal, CMD injection detection
│  + OWASP Secure Headers     │  Adds X-Content-Type-Options, X-Frame-Options, CSP, etc.
└─────────────────────────────┘
     │
     ▼
┌─────────────────────────────┐
│  RateLimitingFilter         │  @Order(2) — IP-based sliding window
│  /auth/**  → 10 req/min     │  Prevents brute-force & DoS
│  general   → 200 req/min    │
└─────────────────────────────┘
     │
     ▼
┌─────────────────────────────┐
│  JwtAuthenticationFilter    │  @Order(Spring default) — validates Bearer token
│  + SecurityEventLogger      │  Logs expired/malformed/invalid JWT events
└─────────────────────────────┘
     │
     ▼
┌─────────────────────────────┐
│  Spring Security RBAC       │  Role-based access control per endpoint
│  @PreAuthorize on services  │  Method-level authorization
└─────────────────────────────┘
     │
     ▼
  Controller / Service
```

---

## 2. OWASP Top 10 Coverage

| # | Category | Control | File |
|---|----------|---------|------|
| A01 | Broken Access Control | RBAC in SecurityConfig + @PreAuthorize | `SecurityConfig.java` |
| A03 | Injection (XSS/SQLi/CMD) | Pattern-matching filter, blocks/logs | `SecurityAuditFilter.java` |
| A04 | Insecure Design (DoS) | IP-based rate limiting, 429 on excess | `RateLimitingFilter.java` |
| A05 | Security Misconfiguration | OWASP Secure Headers on all responses | `SecurityAuditFilter.java` |
| A07 | Auth Failures | JWT validation, expiry/malform logging | `JwtAuthenticationFilter.java` |
| A08 | Software Integrity (File Upload) | Magic bytes, extension whitelist, size limit | `FileSecurityValidator.java` |

---

## 3. Security Controls Detail

### 3.1 Injection Prevention (A03)

**File:** `src/main/java/com/nexgenai/security/SecurityAuditFilter.java`

Detects four attack families via compiled regex:

| Attack | Action | HTTP Response |
|--------|--------|---------------|
| XSS | Block + log | 400 Bad Request |
| SQL Injection | Log only | Chain proceeds |
| Path Traversal | Block + log | 400 Bad Request |
| Command Injection | Block + log | 400 Bad Request |

SQL injection is logged but not blocked at the filter level because the ORM (JPA/Hibernate) uses parameterized queries, making injection structurally impossible. Blocking at the filter could create false-positives on legitimate search queries containing SQL keywords.

**Scanned locations:**
- Request URI (path traversal only)
- All query parameters
- Selected headers: `Referer`, `X-Forwarded-For`, `User-Agent`, `X-Custom-Header`

### 3.2 Security Response Headers (A05)

Added to every non-static response:

```
X-Content-Type-Options:   nosniff
X-Frame-Options:          DENY
X-XSS-Protection:         1; mode=block
Referrer-Policy:          strict-origin-when-cross-origin
Permissions-Policy:       camera=(), microphone=(), geolocation=()
Cache-Control:            no-store
```

### 3.3 Rate Limiting (A04)

**File:** `src/main/java/com/nexgenai/security/RateLimitingFilter.java`

- Sliding window per IP (resets every 60 seconds)
- `/auth/**` endpoints: **10 requests/minute** (brute-force protection)
- All other endpoints: **200 requests/minute** (DoS protection)
- Returns HTTP 429 with `Retry-After: 60` header
- Logs via `SecurityEventLogger.rateLimitExceeded()` and `bruteForce()`

### 3.4 RBAC (A01)

**File:** `src/main/java/com/nexgenai/config/SecurityConfig.java`

| Route | Allowed Roles |
|-------|--------------|
| `GET /jobs/**` | Public |
| `POST/PUT/DELETE /jobs/**` | HR, ADMIN |
| `GET /job-tests/**` | Authenticated |
| `POST/PUT/DELETE /job-tests/**` | HR, ADMIN |
| `/candidate/**` | CANDIDATE |
| `GET /interviews/**` | HR, ADMIN, EVALUATOR |
| `POST/PUT /interviews/**` | HR, ADMIN |
| `/admin/**` | ADMIN |
| `/auth/**` | Public |

Additional method-level security via `@PreAuthorize` annotations on service methods.

### 3.5 JWT Security (A07)

**File:** `src/main/java/com/nexgenai/config/JwtAuthenticationFilter.java`

- Validates HS256 signature on every request
- Expired tokens: logged as `EXPIRED_JWT`, request continues unauthenticated
- Malformed tokens: logged as `INVALID_JWT`, request continues unauthenticated
- `none` algorithm rejected by JJWT library by default
- No `System.out.println` — all events go to `SECURITY_AUDIT` logger

### 3.6 File Upload Security (A08)

**File:** `src/main/java/com/nexgenai/security/FileSecurityValidator.java`

Inject into any controller that accepts file uploads via `@Autowired FileSecurityValidator`.

Validation pipeline:
1. **Null/empty check**
2. **Size limit** — max 10 MB
3. **Filename sanitization** — rejects `../`, `\`, `*`, `?`, `:`
4. **Extension whitelist** — only: `pdf, doc, docx, png, jpg, jpeg, gif, txt, csv, xlsx`
5. **Extension blacklist** — hard-blocks: `exe, bat, sh, php, py, jsp, aspx, jar, ps1...`
6. **Magic bytes check** — PDF, PNG, JPEG, GIF, DOCX/XLSX verified against actual file header

Usage:
```java
@PostMapping("/upload")
public ResponseEntity<?> uploadCv(@RequestParam MultipartFile file,
                                   HttpServletRequest request) {
    var result = fileSecurityValidator.validate(file, getClientIp(request));
    if (!result.valid()) return ResponseEntity.badRequest().body(result.reason());
    // proceed with safe file
}
```

---

## 4. Security Audit Logging

**File:** `src/main/java/com/nexgenai/security/SecurityEventLogger.java`

All security events are written to a dedicated log file, separate from the application log.

### Log files

| File | Purpose | Retention |
|------|---------|-----------|
| `logs/security-audit.log` | All attack attempts, auth events | **90 days** |
| `logs/application.log` | Application errors & info | 30 days |

### Log format

```
2024-01-15 14:23:11.457 | WARN     | XSS_ATTEMPT     | ip=1.2.3.4 | method=GET | url=/api/v1/jobs | location=param:q | payload=<script>ale…[truncated]
2024-01-15 14:23:15.012 | WARN     | SQL_INJECTION    | ip=1.2.3.4 | method=GET | url=/api/v1/jobs | location=param:search | payload=' OR '1'='1
2024-01-15 14:23:20.891 | WARN     | RATE_LIMIT       | ip=1.2.3.4 | url=/auth/login | count=11
2024-01-15 14:23:20.892 | WARN     | BRUTE_FORCE      | ip=1.2.3.4 | email=unknown
2024-01-15 14:25:00.100 | WARN     | INVALID_JWT      | ip=1.2.3.4 | url=/api/v1/admin/users | reason=malformed
2024-01-15 14:26:00.500 | WARN     | MALICIOUS_FILE   | ip=1.2.3.4 | filename=shell.php | reason=Blocked extension: php
```

### Event types

| Event | Trigger |
|-------|---------|
| `XSS_ATTEMPT` | XSS pattern detected in param or header |
| `SQL_INJECTION` | SQL injection pattern detected |
| `PATH_TRAVERSAL` | `../` or encoded equivalent in URI |
| `RATE_LIMIT` | IP exceeds request threshold |
| `BRUTE_FORCE` | Rate limit exceeded on `/auth/**` |
| `MALICIOUS_FILE` | File upload rejected by validator |
| `INVALID_JWT` | Malformed or invalid signature |
| `EXPIRED_JWT` | Valid JWT but past expiry |
| `UNAUTHORIZED` | Access to protected route without auth |
| `FORBIDDEN` | Authenticated user lacks required role |
| `AUTH_SUCCESS` | Successful login |
| `AUTH_FAILURE` | Failed login attempt |

---

## 5. Automated Security Testing

### Unit Tests

| Test Class | Coverage |
|-----------|---------|
| `SecurityAuditFilterTest` | XSS/SQLI/Path Traversal/CMD patterns, header injection, security headers |
| `RateLimitingFilterTest` | Auth limit (10/min), general limit (200/min), X-Forwarded-For, 429 response |
| `FileSecurityValidatorTest` | Magic bytes, extension whitelist/blacklist, size limit, path traversal in name |
| `SecurityEventLoggerTest` | All event methods, null safety, payload truncation |
| `RbacSecurityTest` | Anonymous access → 401, role escalation → 403, authorized roles pass |
| `JwtSecurityTest` | Missing/malformed/expired/none-alg tokens, injection in Authorization header |

Run security tests only:
```bash
./mvnw test -Dspring.profiles.active=test \
  -Dtest="SecurityAuditFilterTest,RateLimitingFilterTest,FileSecurityValidatorTest,RbacSecurityTest,JwtSecurityTest,SecurityEventLoggerTest"
```

Run all tests:
```bash
./mvnw test -Dspring.profiles.active=test
```

### OWASP Dependency-Check (CVE scan)

Scans all Maven dependencies against the NVD database. Fails the build on CVSS ≥ 7.

```bash
./mvnw org.owasp:dependency-check-maven:check
```

Report: `target/dependency-check-report/dependency-check-report.html`

### OWASP ZAP (Dynamic Analysis)

ZAP performs a black-box scan against the running application. Only runs on `main` branch push.

To run locally:
```bash
# 1. Start the application
./mvnw spring-boot:run -Dspring.profiles.active=test

# 2. Run ZAP baseline scan (Docker required)
docker run -t ghcr.io/zaproxy/zaproxy:stable zap-baseline.py \
  -t http://localhost:8080/api/v1 \
  -r zap-report.html
```

### Gitleaks (Secret Detection)

Scans git history and staged changes for hardcoded secrets (passwords, API keys, tokens).
Runs on every push via GitHub Actions.

```bash
# Install gitleaks: https://github.com/gitleaks/gitleaks
gitleaks detect --source . --verbose
```

---

## 6. GitHub Actions Workflows

| Workflow | Trigger | What it does |
|---------|---------|-------------|
| `ci.yml` | push/PR | Build + unit tests + JaCoCo + SonarCloud |
| `security.yml` | push + weekly cron | Security tests + OWASP dep-check + Gitleaks + ZAP |

---

## 7. Required GitHub Secrets

| Secret | Used by | Description |
|--------|---------|-------------|
| `JWT_SECRET` | Both workflows | HS256 signing key (min 256-bit hex) |
| `SONAR_TOKEN` | `ci.yml` | SonarCloud access token |
| `NVD_API_KEY` | `security.yml` | NVD API key for faster CVE downloads (free at nvd.nist.gov) |

---

## 8. Reporting Vulnerabilities

If you discover a security vulnerability, please email `security@nexgenai.com` with:
- A clear description of the vulnerability
- Steps to reproduce
- Potential impact

Do **not** open a public GitHub issue for security vulnerabilities.
