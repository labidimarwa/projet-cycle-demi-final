# Sprint 2 — Documentation Tests Backend (Java + Python)

## Vue d'ensemble

| Catégorie | Fichier | Scénarios |
|---|---|---|
| Sessions RH / psychométriques | `TestSessionServiceTest.java` | 11 |
| Matching CV ↔ Offre | `CvMatchingServiceTest.java` | 10 |
| Microservice Python | `test_matching_service.py` | 24 |

**Total : 45 tests**

---

## 1. Concepts fondamentaux

### Différence Test Unitaire / Test d'Intégration

| Critère | Test Unitaire | Test d'Intégration |
|---|---|---|
| Périmètre | Une seule classe / fonction | Plusieurs composants ensemble |
| Dépendances | Mockées (Mockito / unittest.mock) | Réelles (BDD H2, serveur HTTP) |
| Vitesse | Très rapide (< 1s par test) | Plus lent (démarrage contexte) |
| Isolation | Totale — test indépendant | Partielle — partage de contexte |
| But | Valider la logique métier pure | Valider le câblage entre composants |

**Dans ce sprint** : tous les tests Java sont **unitaires** (Mockito, aucune BDD réelle).
Les tests Python sont **unitaires** (pure functions + FastAPI TestClient sans modèle réel).

---

## 2. Stack technique

### Java

```
Framework    : JUnit 5 (junit-jupiter)
Mocking      : Mockito 5 (@Mock, @InjectMocks, @ExtendWith(MockitoExtension.class))
Assertions   : JUnit 5 Assertions (assertEquals, assertTrue, assertThrows…)
Injection @Value : ReflectionTestUtils.setField(svc, "fieldName", value)
```

### Python

```
Framework    : pytest
HTTP tests   : FastAPI TestClient (httpx)
Mocking      : unittest.mock (patch, MagicMock)
Assertions   : assert + pytest.approx pour les flottants
```

---

## 3. Tests Java — TestSessionService

**Fichier :** `src/test/java/com/nexgenai/service/TestSessionServiceTest.java`

### Mocks utilisés

```java
@Mock AssessmentRepository       assessmentRepository;
@Mock TestSessionRepository      testSessionRepository;
@Mock QuestionRepository         questionRepository;
@Mock CandidateAnswerRepository  candidateAnswerRepository;
@Mock AntiCheatEventRepository   antiCheatEventRepository;
// + 6 autres repositories
@InjectMocks TestSessionService  svc;
```

### Scénarios

| ID | Scénario | Méthode testée | Vérification clé |
|---|---|---|---|
| TC-RH-01 | Test disponible, non encore passé | `startSession()` | Session créée, statut IN_PROGRESS |
| TC-RH-02 | Test déjà passé (COMPLETED) | `startSession()` | `timeLeftSeconds = 0` |
| TC-RH-03 | Assessment inexistant | `startSession()` | `RuntimeException("Assessment not found")` |
| TC-RH-04 | Soumission complète (toutes réponses) | `submitTest()` | Score calculé, statut COMPLETED |
| TC-RH-05 | Soumission partielle (3/4) | `submitTest()` | Soumission réussie malgré questions manquantes |
| TC-RH-06 | Timeout — auto-soumission | `handleTimeout()` | Session COMPLETED, réponses en cours sauvegardées |
| TC-RH-07 | saveRhAnswer — réponse correcte | `saveAnswer()` | CandidateAnswer persisté avec optionIds |
| TC-RH-08 | Anti-triche PASTE détecté | `recordAntiCheatEvent()` | AntiCheatEvent type=PASTE sauvegardé |
| TC-RH-09 | Anti-triche TAB_SWITCH | `recordAntiCheatEvent()` | AntiCheatEvent type=TAB_SWITCH sauvegardé |
| TC-RH-10 | Test déjà COMPLETED → pas de recalcul | `submitTest()` | Retourne score existant, pas de nouveau calcul |
| TC-RH-11 | saveRhAnswer sur session COMPLETED | `saveAnswer()` | RuntimeException "already submitted" |

### Formule anti-triche (TestSessionService)

```
score_risque = tabSwitches × 2 + pastes × 3 + devTools × 5
score < 4  → LOW
score 4–9  → MEDIUM
score ≥ 10 → HIGH
```

> Note : `AntiCheatService` utilise une formule différente (TAB×3 + PASTE×2 + DEVTOOLS×4 + BLUR×1).
> Chaque service a sa propre formule — ne pas les confondre.

---

## 4. Tests Java — CvMatchingService

**Fichier :** `src/test/java/com/nexgenai/service/CvMatchingServiceTest.java`

### Mocks utilisés

```java
@Mock PythonExtractorClient    pythonClient;
@Mock JobRepository            jobRepository;
@Mock MatchingReportRepository reportRepository;
@Mock JobMatchRepository       jobMatchRepository;
@Mock CandidateRepository      candidateRepository;
// ObjectMapper → réel (injecté par ReflectionTestUtils)
```

### Seuils configurables (injectés par `ReflectionTestUtils`)

| Paramètre | Valeur de test | Description |
|---|---|---|
| `seuilMatch` | 0.75 | Cosinus ≥ 0.75 → MATCHED |
| `seuilPartiel` | 0.50 | Cosinus ≥ 0.50 → PARTIAL (sinon MISSING) |
| `seuilRetenir` | 75.0 | Score ≥ 75 → RETENIR |
| `seuilEtudier` | 50.0 | Score ≥ 50 → A_ETUDIER (sinon REJETER) |

### Scénarios

| ID | Scénario | Vérification clé |
|---|---|---|
| TC-SC-01 | Python indisponible | `RuntimeException` message contient "python" ou "indisponible" |
| TC-SC-02 | CV non uploadé (cvPath=null, cvBytes=null) | `RuntimeException` message contient "CV" ou "disponible" |
| TC-SC-03 | Candidat introuvable | `RuntimeException` message contient "candidat" |
| TC-SC-04 | Cache hit (même cvHash) | Rapport retourné, `pythonClient.extraireCv` jamais appelé |
| TC-SC-05 | Skill obligatoire similarity < 0.50 | `forceRejet=true`, `scoreGlobal=0`, `recommendation=REJETER` |
| TC-SC-06 | Prérequis obligatoire score < 0.40 | `forceRejet=true`, `scoreGlobal=0` |
| TC-SC-07 | Tous skills à 1.0 | `scoreGlobal=100`, `recommendation=RETENIR` |
| TC-SC-08 | Score ≥ 75 | `recommendation=RETENIR` |
| TC-SC-09 | Score = 60 | `recommendation=A_ETUDIER` |
| TC-SC-10 | Score < 50 (sans rejet forcé) | `recommendation=REJETER`, `forceRejet=false` |

### Règles métier importantes

```
Rejet forcé — Skill   : skill.obligatory=true AND similarity < seuilPartiel (0.50) → score = 0
Rejet forcé — Prereq  : prereq.obligatory=true AND scoreMatch < 0.40           → score = 0
Cache                 : même (jobId, candidateId, cvHash) → rapport renvoyé sans Python
Score global          : scoreSkills × wSkills + scorePrereqs × wPrereqs
                        wSkills = job.skillsWeight / 100    (défaut 0.70)
                        wPrereqs = job.prerequisitesWeight / 100 (défaut 0.30)
```

---

## 5. Tests Python — Microservice matching

**Fichier :** `matching_service/test_matching_service.py`

### Isolation du modèle IA

Les tests sont isolés de `SentenceTransformer` et `PyMuPDF` pour ne pas
dépendre d'un téléchargement modèle (~400 Mo) ni d'un GPU :

```python
with patch("sentence_transformers.SentenceTransformer", MagicMock()):
    import main as m
m._embed_model = None   # garde les tests indépendants du modèle réel
```

### Scénarios par classe

#### `TestPrefixSkillsMatch` (8 tests)
| ID | Input | Résultat attendu |
|---|---|---|
| TC-PY-01 | "Node" vs "Node.js" | True (suffix "js" connu) |
| TC-PY-02 | "Java" vs "JavaScript" | False ("script" non reconnu) |
| TC-PY-03 | "React" vs "React.js" | True |
| TC-PY-04 | "Python" vs "Python" | True (exact match) |
| TC-PY-05 | "" vs "Python" | False (input vide, len < 3) |
| Bonus | "Vue" vs "Vue.js" | True |
| Bonus | "Angular" vs "Angular.js" | True |
| Bonus | "TypeScript" vs "TypeScript" | True |

**Suffixes reconnus :** `js`, `py`, `ts`, `db`, `2`–`9`

#### `TestSkillsMatch` (6 tests)
| ID | Scénario | Résultat |
|---|---|---|
| TC-PY-06 | Case-insensitive exact | "python" == "Python" → True |
| TC-PY-07 | Unrelated skills | "Ruby" ≠ "JavaScript" → False |
| TC-PY-08 | Empty skill_job | "" → False (garde en tête) |
| TC-PY-08b | Empty skill_cv | → False |
| TC-PY-09 | Prefix fallback (ESCO off) | "Node" == "Node.js" → True |
| Bonus | ESCO match override | Si ESCO → True, retourne True même si prefix échouerait |

**Chaîne de matching :** Exact → ESCO ontologie → Prefix fallback

#### `TestExtractJson` (5 tests)
| ID | Input | Résultat |
|---|---|---|
| TC-PY-10 | JSON valide | Dict correct |
| TC-PY-11 | ```json ... ``` | Dict (balises supprimées) |
| TC-PY-12 | Non-JSON | Dict vide `{}` |
| Bonus | Virgule trailing | Dict (nettoyée) |
| Bonus | JSON imbriqué | Dict imbriqué |

#### `TestCosineSimNp` (4 tests)
| ID | Vecteurs | Résultat |
|---|---|---|
| TC-PY-13 | v, v | ≈ 1.0 |
| TC-PY-14 | [1,0], [0,1] | ≈ 0.0 |
| TC-PY-15 | [0,0,0], v | = 0.0 (pas de division par zéro) |
| Bonus | v, -v | ≈ -1.0 |

#### `TestHealthEndpoint` (2 tests)
| ID | Requête | Vérification |
|---|---|---|
| TC-PY-16 | GET /health | Status 200, `{"status": "ok"}` |
| Bonus | GET /health | Champ `"esco"` présent |

#### `TestEmbedEndpoint` (2 tests)
| ID | Input | Vérification |
|---|---|---|
| TC-PY-17 | POST /embed `{"textes": []}` | `{"embeddings": {}}` |
| Bonus | POST /embed + mock model | Dict de vecteurs retourné |

#### `TestRecalculateExperience` (3 tests)
| ID | Scénario | Vérification |
|---|---|---|
| TC-PY-18 | Fin = "présent" | total_months > 0 |
| TC-PY-19 | Aucune position | total_months = 0 |
| Bonus | Plage exacte 2022→2024 | total_months ≈ 24 |

#### `TestBuildExtractionFields` (4 tests)
| ID | Input | Vérification |
|---|---|---|
| TC-PY-20 | needs_hard=True, needs_soft=True | Champs "hard_skills" + "soft_skills" présents |
| Bonus | Aucun prérequis, pas de skills | Fallback "hard_skills" présent |
| Bonus | Prérequis DEGREE | Champ "degree" présent |
| Bonus | Prérequis EXPERIENCE | Champ "experience" présent |

---

## 6. Lancer les tests

### Tests Java (Maven)

```bash
# Depuis la racine du projet backend
cd C:\cycle\backend-demi-final-pfe

# Lancer tous les tests
mvn test

# Lancer uniquement TestSessionService
mvn test -Dtest=TestSessionServiceTest

# Lancer uniquement CvMatchingService
mvn test -Dtest=CvMatchingServiceTest

# Lancer tous les tests service
mvn test -Dtest="com.nexgenai.service.*"

# Rapport Surefire (dans target/surefire-reports/)
mvn surefire-report:report
```

### Tests Python (pytest)

```bash
# Depuis le répertoire matching_service
cd C:\cycle\matching_service

# Installer les dépendances de test (si pas déjà fait)
pip install pytest httpx fastapi[standard]

# Lancer tous les tests
pytest test_matching_service.py -v

# Lancer une classe spécifique
pytest test_matching_service.py::TestPrefixSkillsMatch -v

# Lancer un test spécifique
pytest test_matching_service.py::TestSkillsMatch::test_case_insensitive_exact_match -v

# Avec rapport de couverture (nécessite pytest-cov)
pip install pytest-cov
pytest test_matching_service.py --cov=main --cov-report=term-missing
```

---

## 7. Structure des répertoires de test

```
backend-demi-final-pfe/
└── src/test/java/com/nexgenai/
    ├── service/
    │   ├── AntiCheatServiceTest.java       (existant)
    │   ├── TestSessionServiceTest.java     ← Sprint 2 (TC-RH-01..11)
    │   └── CvMatchingServiceTest.java      ← Sprint 2 (TC-SC-01..10)
    ├── controller/
    │   ├── AuthControllerTest.java
    │   ├── CandidateControllerTest.java
    │   ├── JobControllerTest.java
    │   └── UserControllerTest.java
    ├── security/ (7 fichiers)
    └── repository/ (3 fichiers)

matching_service/
└── test_matching_service.py               ← Sprint 2 (TC-PY-01..20+)
```

---

## 8. Bonnes pratiques appliquées

### Pattern Given / When / Then

Chaque test Java suit le pattern **GWT** en commentaires :

```java
@Test
void someTest() {
    // Given — état initial et mocks
    when(repo.findById("id")).thenReturn(Optional.of(entity));

    // When — action testée
    var result = svc.doSomething("id");

    // Then — vérifications
    assertEquals("expected", result.getField());
    verify(repo, times(1)).save(any());
}
```

### Isolation totale

- Tous les repositories sont mockés → aucune BDD réelle
- Python SentenceTransformer mocké → aucun téléchargement modèle
- `@Value` injectés par `ReflectionTestUtils` → aucun fichier `application.properties` requis

### Assertions significatives

- `assertTrue(msg.contains("java") || msg.contains("obligatoire"))` plutôt que juste vérifier la classe d'exception
- `assertFalse(dto.isForceRejet(), "This is a soft reject, not a forced one")` — message d'erreur explicatif
- `verify(pythonClient, never()).extraireCv(...)` pour vérifier qu'un appel coûteux n'a pas eu lieu

---

## 9. Couverture fonctionnelle

| Règle métier | Couvert par |
|---|---|
| Python indisponible → message clair | TC-SC-01 |
| CV manquant → message clair | TC-SC-02 |
| Candidat inconnu → exception | TC-SC-03 |
| Cache : même cvHash → pas d'appel Python | TC-SC-04 |
| Skill obligatoire manquant → score=0 | TC-SC-05 |
| Prérequis obligatoire absent → score=0 | TC-SC-06 |
| Score parfait → RETENIR | TC-SC-07 |
| Seuils de recommandation (75/50) | TC-SC-08/09/10 |
| Session non démarrée → IN_PROGRESS | TC-RH-01 |
| Session COMPLETED → timeLeft=0 | TC-RH-02 |
| Assessment inexistant | TC-RH-03 |
| Calcul score soumission complète/partielle | TC-RH-04/05 |
| Timeout auto-soumission | TC-RH-06 |
| Persistance réponses | TC-RH-07 |
| Enregistrement événements anti-triche | TC-RH-08/09 |
| Pas de recalcul si déjà COMPLETED | TC-RH-10 |
| Rejet réponse si session terminée | TC-RH-11 |
| prefix_skills_match (Node/Node.js, Java/JavaScript) | TC-PY-01..05 |
| skills_match chaîne exact→ESCO→prefix | TC-PY-06..09 |
| extract_json robuste (markdown, erreurs) | TC-PY-10..12 |
| cosine_sim_np sans division par zéro | TC-PY-13..15 |
| /health endpoint | TC-PY-16 |
| /embed endpoint vide | TC-PY-17 |
| recalculate_experience (présent, vide) | TC-PY-18/19 |
| build_extraction_fields (DEGREE, EXPERIENCE) | TC-PY-20 |
