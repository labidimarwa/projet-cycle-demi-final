# Guide de test Postman — Sprint 2
# NexGenAI API — Tests d'intégration complets

---

## Configuration de base

| Paramètre | Valeur |
|---|---|
| Base URL | `http://localhost:8080/api/v1` |
| Content-Type | `application/json` |
| Auth type | Bearer Token (JWT) |

### Démarrer le backend

```bash
cd C:\cycle\backend-demi-final-pfe
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

---

## Étape 1 — Créer un environnement Postman

Dans Postman → Environments → New :

| Variable | Valeur initiale |
|---|---|
| `base_url` | `http://localhost:8080/api/v1` |
| `candidate_token` | *(vide, sera rempli après login)* |
| `hr_token` | *(vide, sera rempli après login)* |
| `admin_token` | *(vide, sera rempli après login)* |
| `job_id` | *(vide, sera rempli après création)* |
| `test_id` | *(vide, sera rempli après création)* |
| `theme_id` | *(vide, sera rempli après création)* |
| `candidate_id` | *(vide, sera rempli après inscription)* |
| `session_id` | *(vide, sera rempli après démarrage du test)* |

---

## Étape 2 — Authentification

### POST /auth/register — Créer un compte candidat

```
POST {{base_url}}/auth/register
Content-Type: application/json
```

**Body :**
```json
{
  "firstName": "Alice",
  "lastName": "Martin",
  "email": "alice.martin@test.com",
  "password": "Test@1234!",
  "confirmPassword": "Test@1234!"
}
```

**Réponse attendue (201) :**
```json
{
  "token": "eyJhbGciOiJIUzUxMiJ9...",
  "id": "c8f4a2b1-...",
  "email": "alice.martin@test.com",
  "role": "CANDIDATE",
  "firstName": "Alice",
  "lastName": "Martin"
}
```

**Script Post-request :** Copier dans Postman → Tests :
```javascript
const body = pm.response.json();
pm.environment.set("candidate_token", body.token);
pm.environment.set("candidate_id", body.id);
```

---

### POST /auth/login — Login candidat

```
POST {{base_url}}/auth/login
Content-Type: application/json
```

**Body :**
```json
{
  "email": "alice.martin@test.com",
  "password": "Test@1234!"
}
```

**Réponse attendue (200) :**
```json
{
  "token": "eyJhbGciOiJIUzUxMiJ9...",
  "id": "c8f4a2b1-...",
  "email": "alice.martin@test.com",
  "role": "CANDIDATE"
}
```

**Script Post-request :**
```javascript
pm.environment.set("candidate_token", pm.response.json().token);
```

---

### POST /auth/login — Login RH

```
POST {{base_url}}/auth/login
Content-Type: application/json
```

**Body :**
```json
{
  "email": "rh@nexgenai.com",
  "password": "HR@SecurePass1!"
}
```

**Script Post-request :**
```javascript
pm.environment.set("hr_token", pm.response.json().token);
pm.environment.set("hr_id", pm.response.json().id);
```

---

## US-19 — Découverte des offres (CANDIDAT)

### GET /jobs/active — Liste des offres actives

```
GET {{base_url}}/jobs/active
Authorization: Bearer {{candidate_token}}
```

**Réponse attendue (200) :**
```json
[
  {
    "id": "job-uuid",
    "title": "Développeur Java Senior",
    "department": "Engineering",
    "location": "Paris",
    "contractType": "FULL_TIME",
    "experienceLevel": "SENIOR",
    "status": "OPEN"
  }
]
```

---

### GET /api/v1/candidate/matches/{jobId}/compute — Calculer le score de matching (SSE)

```
GET {{base_url}}/candidate/matches/{{job_id}}/compute
Authorization: Bearer {{candidate_token}}
Accept: text/event-stream
```

> **Note :** Endpoint SSE (Server-Sent Events). Dans Postman, envoyer la requête et observer le stream. Le microservice Python doit être démarré.

**Réponse attendue (200 stream) :**
```
data: {"status":"analyzing","progress":25}

data: {"status":"scoring","progress":75}

data: {"scoreGlobal":82.5,"recommendation":"RETENIR","forceRejet":false}
```

---

## US-21 — Liste des candidatures (CANDIDAT)

### GET /candidate/applications — Toutes mes candidatures

```
GET {{base_url}}/candidate/applications
Authorization: Bearer {{candidate_token}}
```

**Réponse attendue (200) :**
```json
[
  {
    "jobId": "job-uuid",
    "jobTitle": "Développeur Java Senior",
    "status": "SHORTLISTED",
    "appliedAt": "2026-05-01T10:30:00",
    "score": 82,
    "hasActiveTest": true,
    "testCategory": "PSYCHOMETRIC"
  }
]
```

---

### GET /candidate/applications/{jobId}/stages — Étapes du processus

```
GET {{base_url}}/candidate/applications/{{job_id}}/stages
Authorization: Bearer {{candidate_token}}
```

**Réponse attendue (200) :**
```json
[
  {
    "order": 1,
    "name": "Screening IA",
    "type": "AI_SCREENING",
    "status": "COMPLETED"
  },
  {
    "order": 2,
    "name": "Test psychométrique",
    "type": "PSYCHOMETRIC",
    "status": "IN_PROGRESS"
  }
]
```

---

## US-22 — Session de test psychométrique (CANDIDAT)

### POST /candidate/tests/{testId}/start — Démarrer le test

```
POST {{base_url}}/candidate/tests/{{test_id}}/start
Authorization: Bearer {{candidate_token}}
Content-Type: application/json
```

**Body :** *(vide)*

**Réponse attendue (200) :**
```json
{
  "sessionId": "sess-uuid",
  "testName": "Big Five Personality",
  "status": "IN_PROGRESS",
  "timeLeftSeconds": 1800,
  "currentQuestionIndex": 0,
  "questions": [
    {
      "id": "q-uuid",
      "text": "Je préfère travailler seul plutôt qu'en équipe.",
      "type": "LIKERT",
      "options": [
        {"id": "o1", "text": "Tout à fait d'accord"},
        {"id": "o2", "text": "D'accord"},
        {"id": "o3", "text": "Neutre"},
        {"id": "o4", "text": "Pas d'accord"},
        {"id": "o5", "text": "Pas du tout d'accord"}
      ]
    }
  ]
}
```

**Script Post-request :**
```javascript
pm.environment.set("session_id", pm.response.json().sessionId);
```

---

### POST /candidate/tests/{testId}/answer — Enregistrer une réponse

```
POST {{base_url}}/candidate/tests/{{test_id}}/answer
Authorization: Bearer {{candidate_token}}
Content-Type: application/json
```

**Body :**
```json
{
  "questionId": "q-uuid",
  "optionIds": ["o3"],
  "sessionId": "{{session_id}}"
}
```

**Réponse attendue (200) :**
```json
{
  "saved": true,
  "questionId": "q-uuid"
}
```

---

### POST /candidate/tests/{testId}/anti-cheat — Signaler un événement de triche

```
POST {{base_url}}/candidate/tests/{{test_id}}/anti-cheat
Authorization: Bearer {{candidate_token}}
Content-Type: application/json
```

**Body :**
```json
{
  "type": "TAB_SWITCH",
  "sessionId": "{{session_id}}",
  "questionIndex": 3,
  "detail": "Changement onglet détecté"
}
```

**Réponse attendue (200) :**
```json
{ "recorded": true }
```

---

### POST /candidate/tests/{testId}/submit — Soumettre le test

```
POST {{base_url}}/candidate/tests/{{test_id}}/submit
Authorization: Bearer {{candidate_token}}
Content-Type: application/json
```

**Body :**
```json
{
  "sessionId": "{{session_id}}"
}
```

**Réponse attendue (200) :**
```json
{
  "score": 76,
  "totalPoints": 100,
  "completedAt": "2026-06-01T14:22:00"
}
```

---

## US-24 — Démarrer un test depuis la liste des candidatures (CANDIDAT)

### GET /candidate/tests/{testId}/questions — Questions du test

```
GET {{base_url}}/candidate/tests/{{test_id}}/questions?sessionId={{session_id}}
Authorization: Bearer {{candidate_token}}
```

**Réponse attendue (200) :**
```json
[
  {
    "id": "q-uuid",
    "text": "Je suis à l'aise pour prendre des décisions rapidement.",
    "type": "RADIO",
    "options": [
      {"id": "o1", "text": "Toujours"},
      {"id": "o2", "text": "Souvent"},
      {"id": "o3", "text": "Rarement"},
      {"id": "o4", "text": "Jamais"}
    ]
  }
]
```

---

## US-26 — Modifier une offre d'emploi (HR)

### PATCH /jobs/{id} — Modification partielle

```
PATCH {{base_url}}/jobs/{{job_id}}
Authorization: Bearer {{hr_token}}
Content-Type: application/json
```

**Body :**
```json
{
  "title": "Développeur Java Senior (modifié)",
  "description": "Nous recherchons un expert Java Spring Boot avec 5+ ans d'expérience.",
  "location": "Lyon",
  "contractType": "FULL_TIME",
  "experienceLevel": "SENIOR",
  "openPositions": 2,
  "technicalSkills": [
    { "name": "Java", "obligatory": true,  "weight": 30, "skillType": "TECHNICAL" },
    { "name": "Spring Boot", "obligatory": true, "weight": 25, "skillType": "TECHNICAL" },
    { "name": "Docker",      "obligatory": false, "weight": 15, "skillType": "TECHNICAL" },
    { "name": "Communication", "obligatory": false, "weight": 10, "skillType": "SOFT" }
  ],
  "workflowStages": [
    { "stageType": "AI_SCREENING",  "name": "Screening IA",          "order": 1 },
    { "stageType": "PSYCHOMETRIC",  "name": "Test psychométrique",   "order": 2 },
    { "stageType": "RH_INTERVIEW",  "name": "Entretien RH",          "order": 3 }
  ]
}
```

**Réponse attendue (200) :**
```json
{
  "id": "{{job_id}}",
  "title": "Développeur Java Senior (modifié)",
  "status": "OPEN",
  "technicalSkills": [ ... ],
  "workflowStages": [ ... ]
}
```

---

## US-30 — Liste des candidats pour un poste (HR)

### GET /hr/jobs/{jobId}/candidates — Liste des candidats

```
GET {{base_url}}/hr/jobs/{{job_id}}/candidates
Authorization: Bearer {{hr_token}}
```

**Réponse attendue (200) :**
```json
{
  "jobId": "{{job_id}}",
  "jobTitle": "Développeur Java Senior",
  "totalCandidates": 12,
  "candidates": [
    {
      "candidateId": "cand-uuid",
      "name": "Alice Martin",
      "email": "alice@test.com",
      "score": 85,
      "status": "SHORTLISTED",
      "chatDone": true,
      "appliedAt": "2026-05-15T09:00:00"
    }
  ]
}
```

---

### GET /hr/jobs/{jobId}/candidates/{candidateId} — Détail d'un candidat

```
GET {{base_url}}/hr/jobs/{{job_id}}/candidates/{{candidate_id}}
Authorization: Bearer {{hr_token}}
```

**Réponse attendue (200) :**
```json
{
  "candidateId": "cand-uuid",
  "name": "Alice Martin",
  "email": "alice@test.com",
  "currentPosition": "Développeuse Full Stack",
  "score": 85,
  "status": "SHORTLISTED",
  "chatTranscript": [ ... ],
  "matchingReport": {
    "scoreGlobal": 85,
    "recommendation": "RETENIR",
    "skills": [ ... ]
  }
}
```

---

## US-31 — Accepter / Refuser un candidat (HR)

### POST /hr/jobs/{jobId}/candidates/{candidateId}/decision — Décision RH

#### Accepter

```
POST {{base_url}}/hr/jobs/{{job_id}}/candidates/{{candidate_id}}/decision
Authorization: Bearer {{hr_token}}
Content-Type: application/json
```

**Body :**
```json
{
  "decision": "ACCEPTED",
  "note": "Excellent profil, très bonne correspondance technique."
}
```

**Réponse attendue (200) :**
```json
{
  "candidateId": "cand-uuid",
  "decision": "ACCEPTED",
  "note": "Excellent profil, très bonne correspondance technique.",
  "decidedAt": "2026-06-01T11:30:00",
  "decidedBy": "hr@nexgenai.com"
}
```

#### Refuser

```
POST {{base_url}}/hr/jobs/{{job_id}}/candidates/{{candidate_id}}/decision
Authorization: Bearer {{hr_token}}
Content-Type: application/json
```

**Body :**
```json
{
  "decision": "REJECTED",
  "note": "Profil intéressant mais expérience insuffisante pour ce poste."
}
```

---

## US-32 — Liste des tests d'un poste (HR)

### GET /job-tests/jobs-with-tests — Jobs avec leurs tests

```
GET {{base_url}}/job-tests/jobs-with-tests
Authorization: Bearer {{hr_token}}
```

**Réponse attendue (200) :**
```json
[
  {
    "jobId": "job-uuid",
    "jobTitle": "Développeur Java Senior",
    "tests": [
      {
        "id": "test-uuid",
        "name": "Big Five Personality",
        "status": "ACTIVE",
        "type": "PSYCHOMETRIC",
        "candidateCount": 8
      }
    ]
  }
]
```

---

### GET /job-tests/{id} — Détail d'un test

```
GET {{base_url}}/job-tests/{{test_id}}
Authorization: Bearer {{hr_token}}
```

**Réponse attendue (200) :**
```json
{
  "id": "test-uuid",
  "name": "Big Five Personality",
  "status": "DRAFT",
  "durationMinutes": 30,
  "themes": [
    {
      "id": "theme-uuid",
      "name": "Ouverture d'esprit",
      "category": "PERSONALITY",
      "models": [
        {
          "id": "tm-uuid",
          "modelName": "Big Five",
          "weight": 1.0,
          "questions": [ ... ]
        }
      ]
    }
  ]
}
```

---

## US-33 — Activer / Supprimer un test (HR)

### PATCH /job-tests/{id}/activate — Activer un test (DRAFT → ACTIVE)

```
PATCH {{base_url}}/job-tests/{{test_id}}/activate
Authorization: Bearer {{hr_token}}
```

**Réponse attendue (200) :**
```json
{
  "id": "test-uuid",
  "status": "ACTIVE",
  "activatedAt": "2026-06-01T12:00:00"
}
```

---

### DELETE /job-tests/{id} — Supprimer un test

```
DELETE {{base_url}}/job-tests/{{test_id}}
Authorization: Bearer {{hr_token}}
```

**Réponse attendue (204) :** *(corps vide)*

---

## US-34 — Ajouter un thème (HR)

### POST /job-tests/{testId}/themes — Ajouter un thème au test

```
POST {{base_url}}/job-tests/{{test_id}}/themes
Authorization: Bearer {{hr_token}}
Content-Type: application/json
```

**Body :**
```json
{
  "name": "Ouverture d'esprit",
  "category": "PERSONALITY"
}
```

**Réponse attendue (200) :**
```json
{
  "id": "test-uuid",
  "themes": [
    {
      "id": "theme-uuid",
      "name": "Ouverture d'esprit",
      "category": "PERSONALITY",
      "models": []
    }
  ]
}
```

**Script Post-request :**
```javascript
const themes = pm.response.json().themes;
if (themes && themes.length > 0) {
  pm.environment.set("theme_id", themes[themes.length - 1].id);
}
```

---

## US-37 — Ajouter un modèle à un thème (HR)

### GET /job-tests/models — Liste des modèles disponibles

```
GET {{base_url}}/job-tests/models
Authorization: Bearer {{hr_token}}
```

**Réponse attendue (200) :**
```json
[
  {
    "id": "model-uuid",
    "name": "Big Five",
    "description": "Modèle de personnalité à 5 dimensions",
    "category": "PERSONALITY"
  },
  {
    "id": "model2-uuid",
    "name": "DISC",
    "description": "Modèle comportemental DISC",
    "category": "BEHAVIORAL"
  }
]
```

---

### POST /job-tests/{testId}/themes/{themeId}/models — Ajouter un modèle au thème

```
POST {{base_url}}/job-tests/{{test_id}}/themes/{{theme_id}}/models
Authorization: Bearer {{hr_token}}
Content-Type: application/json
```

**Body :**
```json
{
  "modelId": "model-uuid",
  "weight": 1.0
}
```

**Réponse attendue (200) :**
```json
{
  "id": "test-uuid",
  "themes": [
    {
      "id": "theme-uuid",
      "name": "Ouverture d'esprit",
      "models": [
        {
          "id": "tm-uuid",
          "modelId": "model-uuid",
          "modelName": "Big Five",
          "weight": 1.0,
          "questions": []
        }
      ]
    }
  ]
}
```

**Script Post-request :**
```javascript
const themes = pm.response.json().themes;
if (themes && themes[0] && themes[0].models && themes[0].models.length > 0) {
  pm.environment.set("theme_model_id", themes[0].models[0].id);
}
```

---

## US-40 — Ajouter une question (HR)

### POST /job-tests/theme-models/{themeModelId}/questions — Ajouter une question

```
POST {{base_url}}/job-tests/theme-models/{{theme_model_id}}/questions
Authorization: Bearer {{hr_token}}
Content-Type: application/json
```

**Body (RADIO) :**
```json
{
  "text": "Je prends facilement des initiatives sans attendre d'être guidé(e).",
  "type": "RADIO",
  "points": 5,
  "options": [
    { "text": "Toujours vrai",        "value": 5, "isCorrect": false },
    { "text": "Souvent vrai",         "value": 4, "isCorrect": false },
    { "text": "Parfois vrai",         "value": 3, "isCorrect": false },
    { "text": "Rarement vrai",        "value": 2, "isCorrect": false },
    { "text": "Jamais vrai",          "value": 1, "isCorrect": false }
  ]
}
```

**Body (CHECKBOX) :**
```json
{
  "text": "Quels mots décrivent le mieux votre style de travail ? (Choisissez tous les mots qui s'appliquent)",
  "type": "CHECKBOX",
  "points": 4,
  "options": [
    { "text": "Organisé(e)",   "value": 1, "isCorrect": true  },
    { "text": "Créatif(ve)",   "value": 1, "isCorrect": true  },
    { "text": "Impulsif(ve)",  "value": 0, "isCorrect": false },
    { "text": "Méthodique",    "value": 1, "isCorrect": true  }
  ]
}
```

**Body (LIKERT) :**
```json
{
  "text": "Je m'adapte facilement aux changements.",
  "type": "LIKERT",
  "points": 5,
  "options": []
}
```

**Body (RANKING) :**
```json
{
  "text": "Classez ces valeurs par ordre d'importance pour vous.",
  "type": "RANKING",
  "points": 4,
  "options": [
    { "text": "Autonomie",    "value": 1, "isCorrect": false },
    { "text": "Collaboration","value": 2, "isCorrect": false },
    { "text": "Performance",  "value": 3, "isCorrect": false },
    { "text": "Innovation",   "value": 4, "isCorrect": false }
  ]
}
```

**Réponse attendue (201) :**
```json
{
  "id": "question-uuid",
  "text": "Je prends facilement des initiatives...",
  "type": "RADIO",
  "points": 5,
  "options": [ ... ]
}
```

**Script Post-request :**
```javascript
pm.environment.set("question_id", pm.response.json().id);
```

---

## US-41 — Modifier une question (HR)

### PUT /job-tests/questions/{questionId} — Modifier une question existante

```
PUT {{base_url}}/job-tests/questions/{{question_id}}
Authorization: Bearer {{hr_token}}
Content-Type: application/json
```

**Body :**
```json
{
  "text": "Je prends facilement des initiatives sans attendre d'être guidé(e). (version modifiée)",
  "type": "RADIO",
  "points": 5,
  "options": [
    { "text": "Tout à fait d'accord",  "value": 5, "isCorrect": false },
    { "text": "D'accord",              "value": 4, "isCorrect": false },
    { "text": "Neutre",                "value": 3, "isCorrect": false },
    { "text": "Pas d'accord",          "value": 2, "isCorrect": false },
    { "text": "Pas du tout d'accord",  "value": 1, "isCorrect": false }
  ]
}
```

**Réponse attendue (200) :**
```json
{
  "id": "question-uuid",
  "text": "Je prends facilement des initiatives... (version modifiée)",
  "type": "RADIO",
  "points": 5,
  "options": [ ... ]
}
```

---

### DELETE /job-tests/questions/{questionId} — Supprimer une question

```
DELETE {{base_url}}/job-tests/questions/{{question_id}}
Authorization: Bearer {{hr_token}}
```

**Réponse attendue (204) :** *(corps vide)*

---

## US-43 — Résultat détaillé d'un candidat (HR)

### GET /job-tests/{testId}/rh-candidates/{candidateId}/result — Résultat test RH

```
GET {{base_url}}/job-tests/{{test_id}}/rh-candidates/{{candidate_id}}/result
Authorization: Bearer {{hr_token}}
```

**Réponse attendue (200) :**
```json
{
  "candidateId": "cand-uuid",
  "candidateName": "Alice Martin",
  "testId": "test-uuid",
  "testName": "Big Five Personality",
  "submissionId": "sess-uuid",
  "score": 78,
  "totalPoints": 100,
  "themes": [
    {
      "themeId": "theme-uuid",
      "themeName": "Ouverture d'esprit",
      "themeCategory": "PERSONALITY",
      "totalScore": 39,
      "maxScore": 50,
      "percentage": 78.0,
      "models": [
        {
          "modelName": "Big Five",
          "score": 39,
          "maxScore": 50
        }
      ]
    }
  ]
}
```

---

### GET /job-tests/sessions/{sessionId}/anti-cheat — Rapport anti-triche

```
GET {{base_url}}/job-tests/sessions/{{session_id}}/anti-cheat
Authorization: Bearer {{hr_token}}
```

**Réponse attendue (200) :**
```json
{
  "sessionId": "sess-uuid",
  "riskLevel": "LOW",
  "riskScore": 3,
  "events": [
    {
      "id": "ev-uuid",
      "type": "TAB_SWITCH",
      "detail": "Changement d'onglet détecté",
      "questionIndex": 3,
      "occurredAt": "2026-06-01T14:05:22"
    }
  ],
  "tabSwitchCount": 1,
  "pasteCount": 0,
  "devToolsCount": 0
}
```

---

### GET /job-tests/{testId}/candidates/{candidateId}/answers — Toutes les réponses

```
GET {{base_url}}/job-tests/{{test_id}}/candidates/{{candidate_id}}/answers
Authorization: Bearer {{hr_token}}
```

**Réponse attendue (200) :**
```json
{
  "candidateId": "cand-uuid",
  "testId": "test-uuid",
  "answers": [
    {
      "questionId": "q-uuid",
      "questionText": "Je prends facilement des initiatives...",
      "questionType": "RADIO",
      "selectedOptions": ["Toujours vrai"],
      "points": 5,
      "earnedPoints": 5
    }
  ]
}
```

---

## US-44 — Approuver / Refuser via drawer (HR)

### POST /job-tests/{testId}/candidates/{candidateId}/decision — Décision sur résultat de test

```
POST {{base_url}}/job-tests/{{test_id}}/candidates/{{candidate_id}}/decision
Authorization: Bearer {{hr_token}}
Content-Type: application/json
```

**Body (Accepter) :**
```json
{
  "decision": "ACCEPTED",
  "note": "Score excellent, aptitudes confirmées."
}
```

**Body (Refuser) :**
```json
{
  "decision": "REJECTED",
  "note": "Score insuffisant pour le poste visé."
}
```

**Réponse attendue (200) :**
```json
{
  "candidateId": "cand-uuid",
  "decision": "ACCEPTED",
  "note": "Score excellent, aptitudes confirmées.",
  "decidedAt": "2026-06-01T15:00:00",
  "decidedBy": "hr@nexgenai.com"
}
```

---

## Score / CV Matching (US-19 enrichi)

### POST /hr/jobs/{jobId}/candidates/{candidateId}/match — Lancer le matching

```
POST {{base_url}}/hr/jobs/{{job_id}}/candidates/{{candidate_id}}/match
Authorization: Bearer {{hr_token}}
Content-Type: multipart/form-data
```

**Body (form-data) :** *(sans CV : utilise le CV stocké en base)*

| Clé | Type | Valeur |
|-----|------|--------|
| *(vide)* | — | *(corps vide, utilise le CV stocké)* |

**Body (form-data avec CV) :**

| Clé | Type | Valeur |
|-----|------|--------|
| `cv` | File | `alice_cv.pdf` |

**Réponse attendue (200) :**
```json
{
  "jobId": "job-uuid",
  "candidateId": "cand-uuid",
  "candidatNom": "Alice Martin",
  "scoreGlobal": 85.5,
  "recommendation": "RETENIR",
  "forceRejet": false,
  "forceRejetRaison": null,
  "scoreSkills": 88.0,
  "scoreSkillsTechnique": 90.0,
  "scoreSkillsSoft": 82.0,
  "scorePrerequisite": 80.0,
  "skills": [
    {
      "nom": "Java",
      "poids": 30,
      "obligatoire": true,
      "similarite": 0.95,
      "statut": "MATCHED",
      "skillType": "TECHNICAL"
    },
    {
      "nom": "Docker",
      "poids": 15,
      "obligatoire": false,
      "similarite": 0.42,
      "statut": "PARTIAL",
      "skillType": "TECHNICAL"
    }
  ],
  "prerequis": [
    {
      "type": "DEGREE",
      "requis": "Bac+5 Informatique",
      "detecte": "Master Informatique — satisfait (score 0.92)",
      "obligatoire": true,
      "scoreMatch": 0.92,
      "satisfait": true
    }
  ],
  "computedAt": "2026-06-01T15:30:00"
}
```

---

### GET /hr/jobs/{jobId}/candidates/{candidateId}/match — Récupérer le rapport existant

```
GET {{base_url}}/hr/jobs/{{job_id}}/candidates/{{candidate_id}}/match
Authorization: Bearer {{hr_token}}
```

**Réponse attendue (200) :** *(même structure que ci-dessus)*

---

## Tests de sécurité avec Postman

### Test 1 — Accès sans token → 401

```
GET {{base_url}}/job-tests
```

*(Aucun header Authorization)*

**Réponse attendue : 401 Unauthorized**

---

### Test 2 — CANDIDATE accède à une route HR → 403

```
POST {{base_url}}/job-tests
Authorization: Bearer {{candidate_token}}
Content-Type: application/json
```

**Body :**
```json
{ "name": "Tentative de création de test" }
```

**Réponse attendue : 403 Forbidden**

---

### Test 3 — XSS dans le nom du thème → 400

```
POST {{base_url}}/job-tests/{{test_id}}/themes
Authorization: Bearer {{hr_token}}
Content-Type: application/json
```

**Body :**
```json
{
  "name": "<script>alert('xss')</script>",
  "category": "PERSONALITY"
}
```

**Réponse attendue : 400 Bad Request**

---

### Test 4 — JWT falsifié → 401

```
GET {{base_url}}/job-tests
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.PAYLOAD_FALSIFIE.SIGNATURE_INVALIDE
```

**Réponse attendue : 401 Unauthorized**

---

### Test 5 — SQL injection dans les paramètres → 400

```
GET {{base_url}}/job-tests?name=' OR '1'='1
Authorization: Bearer {{hr_token}}
```

**Réponse attendue : 400 Bad Request**

---

## Récapitulatif des endpoints Sprint 2

| US | Méthode | URL | Rôle |
|---|---|---|---|
| US-19 | GET | `/api/v1/jobs/active` | PUBLIC |
| US-19 | GET | `/api/v1/candidate/matches/{jobId}/compute` | CANDIDATE |
| US-21 | GET | `/api/v1/candidate/applications` | CANDIDATE |
| US-21 | GET | `/api/v1/candidate/applications/{jobId}/stages` | CANDIDATE |
| US-22 | POST | `/api/v1/candidate/tests/{testId}/start` | CANDIDATE |
| US-22 | POST | `/api/v1/candidate/tests/{testId}/answer` | CANDIDATE |
| US-22 | POST | `/api/v1/candidate/tests/{testId}/anti-cheat` | CANDIDATE |
| US-22 | POST | `/api/v1/candidate/tests/{testId}/submit` | CANDIDATE |
| US-24 | GET | `/api/v1/candidate/tests/{testId}/questions` | CANDIDATE |
| US-26 | PATCH | `/api/v1/jobs/{id}` | HR, ADMIN |
| US-30 | GET | `/api/v1/hr/jobs/{jobId}/candidates` | HR, ADMIN |
| US-30 | GET | `/api/v1/hr/jobs/{jobId}/candidates/{candidateId}` | HR, ADMIN |
| US-31 | POST | `/api/v1/hr/jobs/{jobId}/candidates/{candidateId}/decision` | HR, ADMIN |
| US-32 | GET | `/api/v1/job-tests/jobs-with-tests` | HR, ADMIN |
| US-32 | GET | `/api/v1/job-tests/{id}` | HR, ADMIN |
| US-33 | PATCH | `/api/v1/job-tests/{id}/activate` | HR, ADMIN |
| US-33 | DELETE | `/api/v1/job-tests/{id}` | HR, ADMIN |
| US-34 | POST | `/api/v1/job-tests/{testId}/themes` | HR, ADMIN |
| US-37 | POST | `/api/v1/job-tests/{testId}/themes/{themeId}/models` | HR, ADMIN |
| US-37 | PATCH | `/api/v1/job-tests/{testId}/themes/{themeId}/models/{tmId}/weight` | HR, ADMIN |
| US-40 | POST | `/api/v1/job-tests/theme-models/{tmId}/questions` | HR, ADMIN |
| US-41 | PUT | `/api/v1/job-tests/questions/{questionId}` | HR, ADMIN |
| US-41 | DELETE | `/api/v1/job-tests/questions/{questionId}` | HR, ADMIN |
| US-43 | GET | `/api/v1/job-tests/{testId}/rh-candidates/{candidateId}/result` | HR, ADMIN |
| US-43 | GET | `/api/v1/job-tests/sessions/{sessionId}/anti-cheat` | HR, ADMIN |
| US-43 | GET | `/api/v1/job-tests/{testId}/candidates/{candidateId}/answers` | HR, ADMIN |
| US-44 | POST | `/api/v1/job-tests/{testId}/candidates/{candidateId}/decision` | HR, ADMIN |

---

## Codes d'erreur courants

| Code | Signification | Cause fréquente |
|---|---|---|
| 400 | Bad Request | Validation échouée, XSS détecté, champ obligatoire manquant |
| 401 | Unauthorized | Token manquant, expiré ou invalide |
| 403 | Forbidden | Mauvais rôle (ex: CANDIDATE sur route HR) |
| 404 | Not Found | Ressource inexistante (jobId, testId, candidateId incorrect) |
| 409 | Conflict | Test déjà soumis, décision déjà prise |
| 500 | Internal Server Error | Microservice Python indisponible, erreur BDD |
