# Security Test Cases — NexGenAI

Covers the four OWASP attack surfaces hardened in this project:
1. XSS (Cross-Site Scripting)
2. SQL Injection
3. Broken Access Control (RBAC / OWASP A01)
4. Malicious File Upload

---

## Prerequisites

| Tool | Purpose | Install |
|------|---------|---------|
| **Postman** | API tests (auth headers, payloads) | https://www.postman.com/downloads/ |
| **OWASP ZAP** | Automated scan + manual proxy | https://www.zaproxy.org/download/ |
| Backend running | `mvn spring-boot:run` | port 8080 |
| Frontend running | `ng serve` | port 4200 |

---

## 1. XSS — Cross-Site Scripting

### What was fixed
`discovery.component.ts` previously used `DomSanitizer.bypassSecurityTrustHtml()` to render
job descriptions via `[innerHTML]`. An HR user could insert a malicious script into a job
description and have it execute in every candidate's browser when they viewed that job.

**Fix applied:** replaced `[innerHTML]="safe(job.description)"` with Angular text interpolation
`{{ job.description }}` inside a `whitespace-pre-wrap` div. Angular auto-escapes all HTML entities.

---

### Test 1.1 — Job description XSS payload (manual)

**Steps:**

1. Log in as HR in Postman and obtain a JWT token.
2. Create or update a job with the following description:

```
POST /api/v1/hr/jobs
Authorization: Bearer <hr-token>
Content-Type: application/json

{
  "title": "Test XSS Job",
  "description": "<script>alert('XSS')</script><img src=x onerror=alert('XSS2')>"
}
```

3. Log in as a candidate in the browser and open the Discovery page.
4. Click on the job "Test XSS Job".

**Expected result:** The description panel shows the raw text
`<script>alert('XSS')</script>...` — no alert dialog appears.

**Failure indicator:** A browser `alert()` popup appears → XSS is not sanitized.

---

### Test 1.2 — Stored XSS via profile fields (manual)

**Steps:**

1. Log in as a candidate and call:

```
PATCH /api/v1/candidate/profile
Authorization: Bearer <candidate-token>
Content-Type: application/json

{
  "firstName": "<img src=x onerror=alert(1)>",
  "city": "'; DROP TABLE users; --"
}
```

**Expected result:** HTTP 400 with validation error
`"Invalid characters in first name"` — the payload is rejected before it is stored.

---

### Test 1.3 — ZAP Active Scan (automated)

1. Open OWASP ZAP → **Quick Start** → set target to `http://localhost:4200`.
2. Run **Active Scan**.
3. After the scan, open **Alerts** tab and filter by **Cross Site Scripting**.

**Expected result:** No High-risk XSS alerts on the Discovery page or profile fields.

---

## 2. SQL Injection

### What was fixed
Spring Boot uses JPA/JPQL with parameterized queries throughout — no raw SQL string
concatenation was found. The `SecurityAuditFilter` additionally detects and logs SQLi
patterns in query parameters and blocks some at the HTTP layer.

---

### Test 2.1 — Classic SQL injection in query parameter (Postman)

```
GET /api/v1/jobs?title=' OR '1'='1
Authorization: Bearer <any-token>
```

**Expected result:** HTTP 400 or an empty / normal result set — never a full database dump.
The filter logs a `SQL_INJECTION_ATTEMPT` event in `security-audit.log`.

---

### Test 2.2 — UNION-based injection

```
GET /api/v1/jobs?title=test' UNION SELECT username,password FROM users--
Authorization: Bearer <any-token>
```

**Expected result:** HTTP 400 (blocked by SecurityAuditFilter) or empty results.
No user data is returned.

---

### Test 2.3 — ZAP SQL Injection scan

1. In ZAP, configure your session with a valid JWT as a header:
   **Tools → Options → HTTP Sessions** or use a script to add `Authorization: Bearer <token>`.
2. Spider `http://localhost:8080/api/v1/jobs`.
3. Run **Active Scan** against the spidered URLs.

**Expected result:** No High-severity SQL Injection alerts.

---

## 3. Broken Access Control (RBAC)

### What was fixed
- `SecurityConfig` uses `requestMatchers` with role-based path restrictions
  (`ROLE_HR`, `ROLE_CANDIDATE`, `ROLE_ADMIN`).
- Controllers use `@PreAuthorize("hasRole('HR')")` etc.
- Candidate can only access their own data (JWT `sub` claim = their email, enforced in services).

---

### Test 3.1 — Candidate tries to access HR endpoint (Postman)

```
GET /api/v1/hr/jobs
Authorization: Bearer <candidate-token>
```

**Expected result:** HTTP 403 Forbidden.

---

### Test 3.2 — Unauthenticated access to protected route

```
GET /api/v1/candidate/profile
(no Authorization header)
```

**Expected result:** HTTP 401 Unauthorized.

---

### Test 3.3 — Candidate accesses another candidate's CV

Suppose Candidate A has email `alice@test.com` and Candidate B has email `bob@test.com`.

1. Log in as Candidate B, obtain token.
2. Try to download Candidate A's CV:

```
GET /api/v1/candidate/cv/download
Authorization: Bearer <bob-token>
```

**Expected result:** Returns only Bob's CV. Alice's CV path is not accessible because
the backend resolves the path from the JWT subject (email), not a URL parameter.

---

### Test 3.4 — HR accesses candidate data for a job they didn't create

```
GET /api/v1/hr/jobs/{jobId-created-by-other-hr}/candidates
Authorization: Bearer <different-hr-token>
```

**Expected result:** HTTP 403 or empty list depending on configuration.
HR users should only manage jobs they created.

---

### Test 3.5 — ZAP Access Control Testing

1. Record two sessions in ZAP: one as HR, one as candidate.
2. Use **Access Control Testing** (right-click on site → Active Scan).
3. Configure the candidate session for HR URLs.

**Expected result:** All HR URLs return 403 when accessed with the candidate session.

---

## 4. Malicious File Upload

### What was fixed
`FileSecurityValidator` is now called in:
- `CandidateService.uploadCv()` — profile CV upload
- `ApplicationService.submitApplication()` — CV attached to job application
- `AssessmentCrudService.uploadQuestionImage()` — question image upload

Validation chain (per file):
1. **Size** — max 10 MB; rejects DoS via large file
2. **Filename** — rejects path traversal chars (`../`, `..\\`, `%2F`, `%252E`, etc.)
3. **Extension whitelist** — only `pdf, doc, docx, png, jpg, jpeg, gif, txt, csv, xlsx`
4. **Extension blocklist** — immediately rejects `exe, bat, sh, cmd, ps1, js, jar, php, py…`
5. **Magic bytes** — verifies file content matches declared extension (prevents extension spoofing)

---

### Test 4.1 — Upload an executable renamed as PDF (Postman)

1. Create a file `malware.pdf` whose content is a Windows PE executable (starts with `MZ`
   or any non-PDF bytes). On Windows, you can rename `cmd.exe` to `malware.pdf`.
2. Upload it:

```
POST /api/v1/candidate/cv
Authorization: Bearer <candidate-token>
Content-Type: multipart/form-data
file: malware.pdf
```

**Expected result:** HTTP 400
```json
{ "error": "File rejected: Magic bytes mismatch for extension: pdf" }
```

---

### Test 4.2 — Upload a shell script (.sh)

```
POST /api/v1/candidate/cv
Authorization: Bearer <candidate-token>
Content-Type: multipart/form-data
file: exploit.sh   (content: #!/bin/bash\nrm -rf /)
```

**Expected result:** HTTP 400
```json
{ "error": "File rejected: Blocked extension: sh" }
```

---

### Test 4.3 — DoS via oversized file

Create a file larger than 10 MB (e.g. `dd if=/dev/zero of=big.pdf bs=1M count=11` on Linux,
or any 11 MB binary on Windows).

```
POST /api/v1/candidate/cv
Authorization: Bearer <candidate-token>
Content-Type: multipart/form-data
file: big.pdf (11 MB)
```

**Expected result:** HTTP 400
```json
{ "error": "File rejected: File too large: 11534336 bytes" }
```

> Note: Spring Boot also enforces `spring.servlet.multipart.max-file-size=10MB`
> in `application.properties` as a first line of defence (HTTP 413 before the service layer).

---

### Test 4.4 — Path traversal in filename

```
POST /api/v1/candidate/cv
Authorization: Bearer <candidate-token>
Content-Type: multipart/form-data
file: ../../etc/passwd.pdf
```

**Expected result:** HTTP 400
```json
{ "error": "File rejected: Unsafe filename: ../../etc/passwd.pdf" }
```

---

### Test 4.5 — Valid PDF upload (regression / happy path)

Upload a real PDF CV (any legitimate resume PDF ≤ 10 MB with a `.pdf` extension).

**Expected result:** HTTP 200 with the saved file path. The file is stored and the
candidate's `cvPath` is updated in the database.

---

### Test 4.6 — ZAP File Upload Fuzzing

1. In ZAP, intercept the CV upload request via the proxy.
2. Use **Fuzzer** on the `filename` parameter with the FuzzDB file upload payloads:
   `../../../etc/passwd`, `shell.php`, `test.exe`, etc.

**Expected result:** All fuzzing payloads return HTTP 400. No file is stored on disk.

---

## Security Audit Log Verification

After running the tests above, check `logs/security-audit.log` (or the console) for events:

```
SECURITY [MALICIOUS_FILE_UPLOAD] ip=127.0.0.1 file=malware.pdf reason=Magic bytes mismatch...
SECURITY [SQL_INJECTION_ATTEMPT] ip=127.0.0.1 GET /api/v1/jobs param:title value=' OR '1'='1
SECURITY [XSS_ATTEMPT]           ip=127.0.0.1 POST /api/v1/candidate/profile param:firstName ...
SECURITY [PATH_TRAVERSAL]        ip=127.0.0.1 GET /api/v1/../../etc/passwd
```

Every attack attempt must be logged. Missing log entries indicate the validator was not called.

---

## Response Headers Verification (OWASP Secure Headers)

Send any API request and verify the response headers:

```
curl -I http://localhost:8080/api/v1/jobs

Expected headers:
  X-Content-Type-Options: nosniff
  X-Frame-Options: DENY
  X-XSS-Protection: 1; mode=block
  Referrer-Policy: strict-origin-when-cross-origin
  Permissions-Policy: camera=(), microphone=(), geolocation=()
  Content-Security-Policy: default-src 'self'; frame-ancestors 'none'; object-src 'none'
  Cache-Control: no-store
```

In OWASP ZAP, the **Passive Scan** alerts **Missing Anti-clickjacking Header** and
**X-Content-Type-Options Header Missing** should both be absent after these headers are set.
