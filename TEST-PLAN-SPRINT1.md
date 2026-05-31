# Plan de Tests — Sprint 1 : NexGenAI

**Projet :** NexGenAI — Plateforme de recrutement augmentée par IA  
**Sprint :** Sprint 1 — Fonctionnalités cœur (authentification, annonces, candidature)  
**Couverture cible :** ≥ 60 % sur les services testés (JaCoCo)  
**Framework :** JUnit 5 · Spring Boot Test · Mockito · H2 (in-memory)  
**CI/CD :** GitHub Actions — déclenché à chaque `push` et `pull_request` sur `main`

---

## 1. Cas d'Utilisation Critiques du Sprint 1

| ID   | Acteur           | Description                                                         | Priorité |
|------|------------------|---------------------------------------------------------------------|----------|
| US-01 | Tout utilisateur | Se connecter avec ses identifiants pour accéder à son espace       | Haute    |
| US-13 | Candidat         | Créer un compte pour postuler aux offres disponibles               | Haute    |
| US-14 | Candidat         | Consulter ses informations de profil                               | Haute    |
| US-15 | Candidat         | Modifier ses informations de profil                                | Haute    |
| US-16 | Candidat         | Consulter la liste des offres d'emploi actives                     | Haute    |
| US-17 | Candidat         | Filtrer les offres par critères (département)                      | Moyenne  |
| US-18 | Candidat         | Consulter le détail d'une offre d'emploi                           | Haute    |
| US-20 | Candidat         | Postuler à une annonce en important son CV                         | Haute    |
| US-25 | Responsable RH   | Créer une annonce d'emploi                                         | Haute    |
| US-28 | Responsable RH   | Changer le statut d'une annonce                                    | Haute    |
| US-29 | Responsable RH   | Consulter la liste de ses annonces                                 | Haute    |
| US-45 | Administrateur   | Créer les comptes des responsables RH et évaluateurs              | Haute    |
| US-47 | Administrateur   | Consulter la liste des utilisateurs                                | Moyenne  |

---

## 2. Architecture de Tests

```
src/test/java/com/nexgenai/
│
├── controller/
│   ├── AuthControllerTest.java        ← US-01, US-13
│   ├── JobControllerTest.java         ← US-16, US-17, US-18, US-25, US-28, US-29
│   ├── CandidateControllerTest.java   ← US-14, US-15, US-20  [Sprint 1 - nouveau]
│   └── UserControllerTest.java        ← US-45, US-47         [Sprint 1 - nouveau]
│
├── service/
│   ├── AuthServiceTest.java           ← US-01, US-13
│   ├── JobServiceTest.java            ← US-25, US-28
│   └── UserServiceTest.java           ← US-45, US-47
│
└── integration/
    └── FullAuthFlowTest.java          ← Flux E2E complet (register → login → accès)
```

**Total :** 22 classes de test · ~120 cas de test individuels

---

## 3. Description des Tests par User Story

---

### US-01 — Connexion (login)

**Fichiers :** `AuthServiceTest.java`, `AuthControllerTest.java`

| Code test | But | Description générale |
|-----------|-----|----------------------|
| TC-AUTH-01 | login(Candidate) retourne tokens + champs profil | Vérifie que `AuthService.login()` retourne une `LoginResponse` complète (token, refreshToken, id, email, prénom, nom, userType, expiresIn, currentPosition, yearsOfExperience, educationLevel) quand les identifiants sont valides |
| TC-AUTH-02 | login(HR) inclut department et position | Vérifie que pour un utilisateur de type RH, la réponse contient les champs `department` et `position` |
| TC-AUTH-03 | login(TechEvaluator) inclut specialization et expertiseLevel | Vérifie les champs spécifiques à l'évaluateur |
| TC-AUTH-04 | login appelle updateLastLogin exactement une fois | Vérifie que la dernière connexion est bien enregistrée en base |
| TC-AUTH-05 | Mauvais mot de passe → BadCredentialsException propagée | Le service de Spring Security lève une exception — jamais de token retourné |
| TC-AUTH-06 | Utilisateur introuvable après auth → RuntimeException | Cas théorique : l'auth réussit mais l'utilisateur n'existe pas en base |
| TC-ACTRL-06 | Login valide via HTTP → 200 + token JWT | Test d'intégration : POST /auth/login avec identifiants corrects → réponse JSON avec token |
| TC-ACTRL-07 | Login mauvais mot de passe → 4xx | POST /auth/login avec mauvais mot de passe → HTTP 401 |
| TC-ACTRL-08 | Login email inexistant → 4xx | POST /auth/login avec email inconnu → HTTP 401 |
| TC-ACTRL-09 | Login body vide → 4xx | POST /auth/login sans email ni mot de passe → HTTP 400 |

---

### US-13 — Inscription candidat

**Fichiers :** `AuthServiceTest.java`, `AuthControllerTest.java`

| Code test | But | Description générale |
|-----------|-----|----------------------|
| TC-AUTH-07 | registerCandidate retourne tokens et champs | Vérifie que `AuthService.registerCandidate()` crée un `Candidate`, génère un token JWT et un refresh token, et retourne tous les champs attendus dans `RegisterResponse` |
| TC-AUTH-08 | Mot de passe encodé avant sauvegarde | Vérifie via `verify(userRepository).save(argThat(...))` que le mot de passe sauvegardé est le hash BCrypt, jamais le mot de passe en clair |
| TC-AUTH-09 | Unicité email vérifiée | Vérifie que `userRepository.existsByEmail()` est appelé exactement une fois |
| TC-AUTH-10 | Email dupliqué → RuntimeException, jamais de save | Si l'email existe déjà, une exception est levée AVANT l'appel à `save()` |
| TC-ACTRL-03 | Inscription valide via HTTP → 201 + token | POST /auth/register avec données valides → HTTP 201 + JSON contenant token et email |
| TC-ACTRL-04 | Mots de passe non concordants → 400 | POST /auth/register avec `password ≠ confirmPassword` → HTTP 400 |
| TC-ACTRL-05 | Email dupliqué via HTTP → 4xx ou exception | Deuxième inscription avec le même email → HTTP ≥ 400 |

---

### US-14 — Consulter le profil candidat

**Fichiers :** `CandidateControllerTest.java`

| Code test | But | Description générale |
|-----------|-----|----------------------|
| TC-CCTRL-01 | GET /candidate/profile sans auth → 401 | Vérifie que l'endpoint est protégé : sans token Bearer, la réponse est HTTP 401 Unauthorized |
| TC-CCTRL-02 | GET /candidate/profile avec token candidat → 200 + données | Inscrit un candidat de test, utilise son JWT pour GET /candidate/profile et vérifie que la réponse contient son email et son prénom |

---

### US-15 — Modifier le profil candidat

**Fichiers :** `CandidateControllerTest.java`

| Code test | But | Description générale |
|-----------|-----|----------------------|
| TC-CCTRL-03 | PUT /candidate/profile données valides → 200 | PUT avec `firstName`, `lastName`, `city` valides → HTTP 200 + réponse contenant le prénom modifié |
| TC-CCTRL-04 | PUT /candidate/profile sans auth → 401 | Sans token, PUT /candidate/profile → HTTP 401 |
| TC-CCTRL-05 | PUT /candidate/profile injection HTML dans firstName → 400 | `firstName: "<script>alert('xss')</script>"` est rejeté par la validation `@Pattern` du DTO (OWASP A03) |
| TC-CCTRL-06 | PUT /candidate/profile URL javascript: → 400 | `linkedinUrl: "javascript:alert(1)"` est rejeté par `@Pattern(regexp = "^(https?://.*)?$")` |
| TC-CCTRL-07 | PUT /candidate/profile URL HTTPS valide → 200 | `linkedinUrl: "https://linkedin.com/..."` est accepté → HTTP 200 |

---

### US-16 & US-17 — Consulter et filtrer les offres

**Fichiers :** `JobControllerTest.java`, `JobServiceTest.java`

| Code test | But | Description générale |
|-----------|-----|----------------------|
| TC-JCTRL-01 | GET /jobs sans auth → 200 (endpoint public) | La liste des annonces est accessible sans authentification |
| TC-JCTRL-02 | GET /jobs/active sans auth → 200 | Les annonces actives sont publiques |
| TC-JCTRL-03 | GET /jobs/department/IT sans auth → 200 | Le filtre par département est public |
| JobSvc getActiveJobs | getActiveJobs retourne uniquement ACTIVE | `jobRepository.findByStatus(ACTIVE)` est appelé ; seuls les jobs avec statut ACTIVE sont retournés |
| JobSvc getByDepartment | getJobsByDepartment filtre par département | Vérifie le résultat du filtre et le cas "département inexistant → liste vide" |

---

### US-18 — Consulter le détail d'une offre

**Fichiers :** `JobControllerTest.java`, `JobServiceTest.java`

| Code test | But | Description générale |
|-----------|-----|----------------------|
| TC-JCTRL-08 | GET /jobs/{id} existant → 200 + job en réponse | Crée un job, puis le récupère par ID → HTTP 200 avec le titre du job dans la réponse |
| TC-JCTRL-09 | GET /jobs/{id} inexistant → 4xx ou 5xx | ID qui n'existe pas → exception ou code ≥ 400 |
| JobSvc getById | getJobById ID existant → retourne JobResponse complet | Vérifie l'ID, le titre, et le compte de candidatures dans la réponse |
| JobSvc getById err | getJobById ID inexistant → RuntimeException | L'exception contient l'ID manquant dans son message |

---

### US-20 — Postuler à une annonce

**Fichiers :** `CandidateControllerTest.java`

| Code test | But | Description générale |
|-----------|-----|----------------------|
| TC-CCTRL-08 | [Setup] POST /jobs en tant que HR → annonce créée | Test de préparation : crée une annonce via `@WithMockUser(roles = "HR")` et stocke son ID pour les tests suivants |
| TC-CCTRL-09 | POST /candidate/apply sans auth → 401 | Sans token, la postulation est refusée avec HTTP 401 |
| TC-CCTRL-10 | POST /candidate/apply avec CV PDF valide → 200 | Envoie un fichier PDF minimal (magic bytes `%PDF-1.4`) via multipart/form-data avec le token candidat → HTTP 200 + "Application submitted!" |
| TC-CCTRL-11 | POST /candidate/apply doublon → 200 idempotent | Une deuxième postulation au même job est ignorée silencieusement (HTTP 200, pas d'erreur ni de doublon en base) |
| TC-CCTRL-12 | GET /candidate/applications après candidature → liste non vide | Vérifie que la candidature est bien persistée et consultable |

---

### US-25 — Créer une annonce d'emploi

**Fichiers :** `JobControllerTest.java`, `JobServiceTest.java`

| Code test | But | Description générale |
|-----------|-----|----------------------|
| TC-JCTRL-04 | POST /jobs sans auth → 401/403 | La création d'annonce nécessite une authentification |
| TC-JCTRL-05 | POST /jobs en tant que CANDIDATE → 403 | Un candidat ne peut pas créer d'annonce (RBAC) |
| TC-JCTRL-06 | POST /jobs en tant que HR → 201 + job créé | La réponse contient le titre et l'ID de l'annonce créée |
| TC-JCTRL-07 | POST /jobs en tant que ADMIN → 201 | L'administrateur peut aussi créer des annonces |
| JobSvc create | createJob requête valide → JobResponse avec statut ACTIVE | Vérifie que le job est bien sauvegardé et que les interviews sont créées |
| JobSvc prereqs | createJob avec prérequis → prérequis ajoutés | Les prérequis (EDUCATION, EXPERIENCE…) sont bien attachés à l'entité Job |
| JobSvc skills | createJob avec technicalSkills → skills ajoutés | Les compétences techniques requises sont associées au job |

---

### US-28 — Changer le statut d'une annonce

**Fichiers :** `JobControllerTest.java`, `JobServiceTest.java`

| Code test | But | Description générale |
|-----------|-----|----------------------|
| TC-JCTRL-10 | PATCH /jobs/{id}/status ACTIVE → 200 + statut mis à jour | Vérifie que la réponse contient `"ACTIVE"` après le changement de statut |
| TC-JCTRL-11 | PATCH /jobs/{id}/status sans champ status → 400 | Un corps `{}` sans le champ `status` retourne HTTP 400 |
| JobSvc status | changeStatus ACTIVE→CLOSED → statut persisté | Vérifie via `verify(jobRepository).save(argThat(job -> job.getStatus() == CLOSED))` |
| JobSvc status | changeStatus ID inexistant → RuntimeException | Vérifie que l'exception est bien levée pour un ID inconnu |

---

### US-29 — Consulter la liste des annonces (RH)

**Fichiers :** `JobControllerTest.java`, `JobServiceTest.java`

| Code test | But | Description générale |
|-----------|-----|----------------------|
| TC-JCTRL-01 | GET /jobs → 200 (publique) | L'endpoint GET /jobs est accessible à tous et retourne une liste JSON |
| JobSvc list | getAllJobs retourne la liste complète | Vérifie la taille, les titres et le nombre de candidatures pour chaque job |
| JobSvc list | getAllJobs liste vide → retourne liste vide | Le cas "aucun job" est géré sans exception |

---

### US-45 — Créer les comptes RH et évaluateurs

**Fichiers :** `UserControllerTest.java`, `UserServiceTest.java`

| Code test | But | Description générale |
|-----------|-----|----------------------|
| TC-UCTRL-06 | POST /users/create sans auth → 401 | L'endpoint est protégé |
| TC-UCTRL-07 | POST /users/create CANDIDATE → 403 | Seul l'ADMIN peut créer des comptes |
| TC-UCTRL-08 | POST /users/create ADMIN + HR valide → 201 | Crée un RH en base H2, la réponse contient l'email et le rôle |
| TC-UCTRL-09 | POST /users/create ADMIN + TECH_EVALUATOR → 201 | Crée un évaluateur technique |
| TC-UCTRL-10 | POST /users/create email dupliqué → ≥ 400 | Le doublon d'email est rejeté avant la sauvegarde |
| TC-UCTRL-11 | POST /users/create rôle invalide → ≥ 400 | Un rôle inconnu lève une `IllegalArgumentException` |
| TC-US-01 | createUser(HR) → entité HR avec department + position | Vérifie via `verify(save(argThat(...)))` que le type, département et poste sont corrects |
| TC-US-02 | createUser(TECH_EVALUATOR) → valeurs par défaut | maxEvaluationsPerDay=3, canCreateTechnicalTests=true, etc. |
| TC-US-03 | Mot de passe encodé BCrypt avant save | Le hash BCrypt est sauvegardé, jamais le mot de passe en clair |
| TC-US-04 | Email de bienvenue envoyé après création | `verify(emailService, times(1)).sendEmail(email, ...)` |

---

### US-47 — Consulter la liste des utilisateurs

**Fichiers :** `UserControllerTest.java`, `UserServiceTest.java`

| Code test | But | Description générale |
|-----------|-----|----------------------|
| TC-UCTRL-01 | GET /users sans auth → 401 | Protégé |
| TC-UCTRL-02 | GET /users CANDIDATE → 403 | RBAC : seul ADMIN |
| TC-UCTRL-03 | GET /users HR → 403 | RBAC : seul ADMIN |
| TC-UCTRL-04 | GET /users ADMIN → 200 + page JSON | Retourne un objet `Page<UserListResponse>` avec `content` et `totalElements` |
| TC-US-16 | getUsers() retourne une page UserListResponse | Vérifie le mapping : email, userType (HR) dans la réponse paginée |

---

## 4. Comment lancer les tests

### Localement (Maven)

```bash
# Se placer à la racine du backend
cd C:\cycle\backend-demi-final-pfe

# Lancer tous les tests avec rapport JaCoCo
.\mvnw.cmd clean verify -Dspring.profiles.active=test

# Lancer uniquement les tests d'un fichier
.\mvnw.cmd test -Dtest=CandidateControllerTest -Dspring.profiles.active=test

# Lancer uniquement les tests d'intégration Sprint 1
.\mvnw.cmd test -Dtest="CandidateControllerTest,UserControllerTest,JobControllerTest,AuthControllerTest" -Dspring.profiles.active=test
```

> **Prérequis :** Java 17, pas besoin de MariaDB ni de Python (H2 in-memory + mocks).

### Via GitHub Actions (CI automatique)

Le pipeline CI se déclenche automatiquement à chaque `push` ou `pull_request` sur `main` :

```
.github/workflows/ci.yml
  └─ job: build-and-test
       └─ ./mvnw clean verify -Dspring.profiles.active=test
```

Pour voir les résultats :
1. Aller sur `https://github.com/labidimarwa/projet-cycle-demi-final/actions`
2. Cliquer sur la dernière exécution
3. Ouvrir le job **"Build, Tests & SonarCloud"**
4. Le rapport JaCoCo est téléchargeable depuis l'onglet **Artifacts** → `jacoco-report`

---

## 5. Voir le rapport de couverture (JaCoCo)

### Après exécution locale

```bash
# Le rapport HTML est généré dans :
target/site/jacoco/index.html

# Ouvrir dans le navigateur (Windows)
start target\site\jacoco\index.html
```

Le rapport affiche :
- Taux de couverture global (lignes, branches, instructions)
- Détail par package et par classe
- Lignes couvertes (vert) / non couvertes (rouge) dans le code source

### Sur GitHub Actions

1. Ouvrir l'onglet **Actions** du dépôt GitHub
2. Cliquer sur une exécution réussie
3. Onglet **Artifacts** → télécharger `jacoco-report`
4. Décompresser et ouvrir `index.html` localement

### Sur SonarCloud (analyse de qualité)

URL du projet : `https://sonarcloud.io/project/overview?id=labidimarwa_projet-cycle-demi-final`

SonarCloud affiche :
- Couverture de code (ligne `Coverage`)
- Bugs, code smells, duplication
- Security Hotspots

---

## 6. Seuil de couverture

Le build **échoue automatiquement** si la couverture globale est inférieure à **30 %** (règle JaCoCo dans `pom.xml`).

```xml
<!-- pom.xml — règle JaCoCo -->
<rule>
  <element>BUNDLE</element>
  <limits>
    <limit>
      <counter>LINE</counter>
      <value>COVEREDRATIO</value>
      <minimum>0.30</minimum>   <!-- 30 % minimum -->
    </limit>
  </limits>
</rule>
```

Les classes exclues du calcul (DTOs, modèles, bootstrap, configuration) :
```
**/dto/**
**/model/**
**/bootstrap/**
**/config/**
**/NexgenaiApplication.class
```

---

## 7. Matrice de traçabilité — User Stories ↔ Tests

| User Story | Tests unitaires (service) | Tests d'intégration (controller) | Tests E2E | Couvert ? |
|------------|--------------------------|----------------------------------|-----------|-----------|
| US-01 Login | TC-AUTH-01 à 06 | TC-ACTRL-06 à 09 | TC-E2E-01, 07 | ✅ |
| US-13 Register | TC-AUTH-07 à 10 | TC-ACTRL-03 à 05 | TC-E2E-01, 04 | ✅ |
| US-14 Voir profil | — | TC-CCTRL-01, 02 | TC-E2E-04 | ✅ |
| US-15 Modifier profil | — | TC-CCTRL-03 à 07 | — | ✅ |
| US-16 Liste offres | JobSvc list | TC-JCTRL-01, 02 | — | ✅ |
| US-17 Filtrer offres | JobSvc getByDept | TC-JCTRL-03 | — | ✅ |
| US-18 Détail offre | JobSvc getById | TC-JCTRL-08, 09 | — | ✅ |
| US-20 Postuler | — | TC-CCTRL-09 à 12 | — | ✅ |
| US-25 Créer annonce | JobSvc create | TC-JCTRL-05 à 07 | — | ✅ |
| US-28 Statut annonce | JobSvc status | TC-JCTRL-10, 11 | — | ✅ |
| US-29 Mes annonces | JobSvc getAllJobs | TC-JCTRL-01 | — | ✅ |
| US-45 Créer comptes | TC-US-01 à 07 | TC-UCTRL-06 à 11 | — | ✅ |
| US-47 Liste utilisateurs | TC-US-16 | TC-UCTRL-01 à 05 | — | ✅ |
