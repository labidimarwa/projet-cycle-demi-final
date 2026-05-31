# NexGenAI — Backend

[![CI/CD Pipeline](https://github.com/labidimarwa/projet-cycle-demi-final/actions/workflows/ci.yml/badge.svg)](https://github.com/labidimarwa/projet-cycle-demi-final/actions/workflows/ci.yml)
[![Security Pipeline](https://github.com/labidimarwa/projet-cycle-demi-final/actions/workflows/security.yml/badge.svg)](https://github.com/labidimarwa/projet-cycle-demi-final/actions/workflows/security.yml)

Spring Boot 3.1.5 · Java 17 · H2 (test) · PostgreSQL (prod) · JWT stateless

---

## Lancer les tests

```bash
# Tous les tests (48 tests)
./mvnw clean verify -Dspring.profiles.active=test

# Sprint 1 uniquement
./mvnw test -Dtest="CandidateControllerTest,UserControllerTest" -Dspring.profiles.active=test

# Tests sécurité uniquement
./mvnw test -Dtest=SecurityIntegrationTest -Dspring.profiles.active=test
```

## Voir les résultats

| Rapport | Chemin |
|---------|--------|
| Résultats bruts (texte) | `target/surefire-reports/*.txt` |
| Résultats XML (CI) | `target/surefire-reports/*.xml` |
| Couverture JaCoCo HTML | `target/site/jacoco/index.html` |

```bash
# Générer + ouvrir la couverture
./mvnw test jacoco:report -Dspring.profiles.active=test
open target/site/jacoco/index.html   # macOS/Linux
start target/site/jacoco/index.html  # Windows
```

---

## Tests automatiques

### Sprint 1 — `CandidateControllerTest` (13 tests)

| ID | User Story | Description |
|----|-----------|-------------|
| TC-CCTRL-01 | US-14 | GET /candidate/profile sans auth → 401 |
| TC-CCTRL-02 | US-14 | GET /candidate/profile avec JWT → 200 + email |
| TC-CCTRL-03 | US-15 | PUT profile données valides → 200 |
| TC-CCTRL-04 | US-15 | PUT profile sans auth → 401 |
| TC-CCTRL-05 | US-15 | PUT profile XSS firstName → 400 |
| TC-CCTRL-06 | US-15 | PUT profile URL javascript: → 400 |
| TC-CCTRL-07 | US-15 | PUT profile URL HTTPS valide → 200 |
| TC-CCTRL-08 | US-20 | Setup : création job HR |
| TC-CCTRL-09 | US-20 | POST apply sans auth → 401 |
| TC-CCTRL-10 | US-20 | POST apply PDF valide → 200 |
| TC-CCTRL-11 | US-20 | POST apply doublon → 200 idempotent |
| TC-CCTRL-12 | US-20 | GET applications après candidature → liste non vide |
| TC-CCTRL-13 | US-16 | GET matches → 200 + JSON |

### Sprint 1 — `UserControllerTest` (15 tests)

| ID | User Story | Description |
|----|-----------|-------------|
| TC-UCTRL-01 | US-47 | GET /users sans auth → 401 |
| TC-UCTRL-02 | US-47 | GET /users CANDIDATE → 403 |
| TC-UCTRL-03 | US-47 | GET /users HR → 403 |
| TC-UCTRL-04 | US-47 | GET /users ADMIN → 200 + page |
| TC-UCTRL-05 | US-47 | GET /users?size=5 → 200 |
| TC-UCTRL-06 | US-45 | POST /users/create sans auth → 401 |
| TC-UCTRL-07 | US-45 | POST /users/create CANDIDATE → 403 |
| TC-UCTRL-08 | US-45 | POST /users/create ADMIN + HR → 201 |
| TC-UCTRL-09 | US-45 | POST /users/create ADMIN + TECH_EVALUATOR → 201 |
| TC-UCTRL-10 | US-45 | POST /users/create email dupliqué → 4xx |
| TC-UCTRL-11 | US-45 | POST /users/create rôle inconnu → 4xx |
| TC-UCTRL-12 | RBAC | DELETE /users CANDIDATE → 403 |
| TC-UCTRL-13 | RBAC | PATCH /users status HR → 403 |
| TC-UCTRL-14 | — | GET /users/hr ADMIN → 200 |
| TC-UCTRL-15 | — | GET /users/admins ADMIN → 200 |

### Sécurité — `SecurityIntegrationTest` (20 tests)

| ID | OWASP | Description |
|----|-------|-------------|
| TC-SEC-01 | A03 XSS | firstName `<img onerror=alert(1)>` → 400 |
| TC-SEC-02 | A03 XSS | city `'; DROP TABLE` → 400 |
| TC-SEC-03 | A03 XSS | GET /jobs?title=`<script>` → 400 (filtre) |
| TC-SEC-04 | A03 XSS | GET /jobs?search=`javascript:` → 400 (filtre) |
| TC-SEC-05 | A03 SQLi | GET /jobs?title=`' OR '1'='1` → 400 |
| TC-SEC-06 | A03 SQLi | GET /jobs?title=`UNION SELECT` → 400 |
| TC-SEC-07 | A03 SQLi | GET /jobs?title=`; DROP TABLE` → 400 |
| TC-SEC-08 | A03 SQLi | GET /jobs?title=normal → 200 (pas de faux positif) |
| TC-SEC-09 | A01 RBAC | CANDIDATE → /hr/jobs → 403 |
| TC-SEC-10 | A01 RBAC | CANDIDATE → /hr/cv → 403 |
| TC-SEC-11 | A01 RBAC | Aucun auth → /candidate/profile → 401 |
| TC-SEC-12 | A01 RBAC | Aucun auth → /hr/cv → 401 |
| TC-SEC-13 | A01 RBAC | Aucun auth → POST apply → 401 |
| TC-SEC-14 | A01 RBAC | HR → /users (admin) → 403 |
| TC-SEC-15 | A08 Upload | PDF avec bytes MZ (exe) → 400 |
| TC-SEC-16 | A08 Upload | Fichier .sh → 400 |
| TC-SEC-17 | A08 Upload | Fichier .exe → 400 |
| TC-SEC-18 | A08 Upload | Filename `../../etc/passwd.pdf` → 400 |
| TC-SEC-19 | A08 Upload | PDF valide → 200 (régression) |
| TC-SEC-20 | A05 Headers | X-Frame-Options, CSP, Referrer-Policy présents |

---

## Secret GitHub requis

| Secret | Description |
|--------|-------------|
| `JWT_SECRET` | Clé HMAC-SHA256 ≥ 32 chars pour signer les tokens JWT |
| `SONAR_TOKEN` | Token SonarCloud (optionnel) |
| `NVD_API_KEY` | Clé NVD pour OWASP Dependency-Check (optionnel) |
