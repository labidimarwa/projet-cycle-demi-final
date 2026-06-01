# Guide JMeter — Test de Performance : Get My Score
## NexGenAI — Endpoint de scoring CV ↔ Offre

---

## Prérequis

| Outil | Version | Téléchargement |
|---|---|---|
| Apache JMeter | 5.6+ | https://jmeter.apache.org/download_jmeter.cgi |
| Java JDK | 11+ | (requis par JMeter) |
| Backend Spring Boot | Démarré sur port **8080** | `./mvnw spring-boot:run` |
| Microservice Python | Démarré sur port **8000** | `uvicorn main:app --port 8000` |

---

## Scénario testé : GET MY SCORE

Le flux complet comprend 4 étapes enchaînées :

```
1. POST /auth/register        → créer le compte candidat
2. POST /auth/login           → récupérer le JWT
3. GET  /candidate/matches    → liste de tous les scores existants
4. GET  /candidate/matches/{jobId}/compute  → calculer le score en temps réel (SSE + Python)
```

---

## Étape 1 — Lancer JMeter

```bash
# Windows
C:\apache-jmeter-5.6\bin\jmeter.bat

# Ou en mode ligne de commande (CI/CD)
jmeter -n -t test_plan_get_score.jmx -l results.jtl -e -o rapport_html/
```

---

## Étape 2 — Créer le Test Plan

**File → New** → Renommer en `NexGenAI — Get My Score Performance`

### 2.1 — User Defined Variables (variables globales)

**Clic droit sur Test Plan → Add → Config Element → User Defined Variables**

| Nom | Valeur |
|---|---|
| `BASE_URL` | `localhost` |
| `PORT` | `8080` |
| `CONTEXT` | `/api/v1` |
| `JOB_ID` | *(coller l'ID du job créé avec le JSON ci-dessous)* |
| `CANDIDATE_EMAIL` | `perf.test@nexgenai.com` |
| `CANDIDATE_PASSWORD` | `PerfTest@2026!` |
| `THREADS` | `10` |
| `RAMP_UP` | `30` |
| `DURATION` | `300` |

---

## Étape 3 — Thread Group 1 : Setup (Authentication)

**Clic droit sur Test Plan → Add → Threads → Thread Group**

Paramètres :
- **Name :** `Setup — Auth`
- **Number of Threads :** `1`
- **Ramp-up :** `1`
- **Loop Count :** `1`
- **Action to be taken after a Sampler error :** `Stop Test`

### 3.1 — HTTP Request Defaults

**Clic droit sur Setup — Auth → Add → Config Element → HTTP Request Defaults**

| Champ | Valeur |
|---|---|
| Protocol | `http` |
| Server Name | `${BASE_URL}` |
| Port | `${PORT}` |

---

### 3.2 — Requête : Inscription candidat

**Clic droit sur Setup — Auth → Add → Sampler → HTTP Request**

| Champ | Valeur |
|---|---|
| Name | `POST Register Candidate` |
| Method | `POST` |
| Path | `${CONTEXT}/auth/register` |
| Body Data | *(voir ci-dessous)* |

**Body :**
```json
{
  "firstName": "Perf",
  "lastName": "Tester",
  "email": "${CANDIDATE_EMAIL}",
  "password": "${CANDIDATE_PASSWORD}",
  "confirmPassword": "${CANDIDATE_PASSWORD}"
}
```

**Ajouter un Header Manager** → Clic droit → Add → Config Element → HTTP Header Manager :

| Name | Value |
|---|---|
| Content-Type | `application/json` |

---

### 3.3 — Requête : Login et extraction du token

**Clic droit sur Setup — Auth → Add → Sampler → HTTP Request**

| Champ | Valeur |
|---|---|
| Name | `POST Login` |
| Method | `POST` |
| Path | `${CONTEXT}/auth/login` |
| Body Data | *(voir ci-dessous)* |

**Body :**
```json
{
  "email": "${CANDIDATE_EMAIL}",
  "password": "${CANDIDATE_PASSWORD}"
}
```

**Ajouter JSON Extractor** (extraction du token) :
**Clic droit sur POST Login → Add → Post Processors → JSON Extractor**

| Champ | Valeur |
|---|---|
| Names of created variables | `JWT_TOKEN` |
| JSON Path expressions | `$.token` |
| Default Value | `TOKEN_NOT_FOUND` |
| Match No. | `0` |

**Ajouter JSON Extractor** (extraction de l'ID candidat) :
**Clic droit sur POST Login → Add → Post Processors → JSON Extractor**

| Champ | Valeur |
|---|---|
| Names of created variables | `CANDIDATE_ID` |
| JSON Path expressions | `$.id` |
| Default Value | `ID_NOT_FOUND` |
| Match No. | `0` |

> **Important :** `JWT_TOKEN` et `CANDIDATE_ID` seront automatiquement disponibles dans tous les Thread Groups suivants car ils sont définis en propriété partagée. Pour les partager entre Thread Groups, ajouter dans le JSR223 PostProcessor :
> ```groovy
> props.put("JWT_TOKEN", vars.get("JWT_TOKEN"))
> props.put("CANDIDATE_ID", vars.get("CANDIDATE_ID"))
> ```

---

## Étape 4 — Thread Group 2 : Test de charge — Get My Score

**Clic droit sur Test Plan → Add → Threads → Thread Group**

Paramètres :
- **Name :** `Load Test — Get My Score`
- **Number of Threads :** `${THREADS}` (10 utilisateurs simultanés)
- **Ramp-up Period :** `${RAMP_UP}` (30 secondes — 1 user/3s)
- **Duration :** `${DURATION}` (300 secondes = 5 minutes)
- **Cocher :** `Specify Thread lifetime` → Duration

---

### 4.1 — HTTP Header Manager (authorization)

**Clic droit sur Load Test → Add → Config Element → HTTP Header Manager**

| Name | Value |
|---|---|
| `Authorization` | `Bearer ${__P(JWT_TOKEN)}` |
| `Content-Type` | `application/json` |
| `Accept` | `application/json` |

---

### 4.2 — Requête 1 : Liste des scores existants

**Clic droit sur Load Test → Add → Sampler → HTTP Request**

| Champ | Valeur |
|---|---|
| Name | `GET /candidate/matches` |
| Method | `GET` |
| Path | `${CONTEXT}/candidate/matches` |

**Ajouter Response Assertion :**
**Clic droit → Add → Assertions → Response Assertion**

| Champ | Valeur |
|---|---|
| Field to Test | `Response Code` |
| Pattern Matching Rules | `Equals` |
| Patterns to Test | `200` |

**Ajouter Duration Assertion :**
**Clic droit → Add → Assertions → Duration Assertion**

| Champ | Valeur |
|---|---|
| Duration in milliseconds | `2000` |

---

### 4.3 — Requête 2 : Calcul du score en temps réel (SSE)

**Clic droit sur Load Test → Add → Sampler → HTTP Request**

| Champ | Valeur |
|---|---|
| Name | `GET /candidate/matches/{jobId}/compute` |
| Method | `GET` |
| Path | `${CONTEXT}/candidate/matches/${JOB_ID}/compute` |

> **Note SSE :** JMeter va attendre que le stream SSE se termine et traiter la réponse complète comme un seul résultat. Réponse attendue : plusieurs lignes `data: {...}` terminées par `data: [DONE]`.

**Ajouter Response Assertion :**

| Champ | Valeur |
|---|---|
| Field to Test | `Response Code` |
| Pattern | `200` |

**Ajouter Response Assertion (contenu) :**

| Champ | Valeur |
|---|---|
| Field to Test | `Response Body` |
| Pattern Matching Rules | `Contains` |
| Patterns to Test | `scoreGlobal` |

**Ajouter Duration Assertion :**

| Champ | Valeur |
|---|---|
| Duration in milliseconds | `30000` |

---

### 4.4 — Timer : Think Time (comportement réaliste)

**Clic droit sur Load Test → Add → Timer → Gaussian Random Timer**

| Champ | Valeur |
|---|---|
| Deviation (in milliseconds) | `500` |
| Constant Delay Offset (in milliseconds) | `2000` |

---

## Étape 5 — Listeners (rapports)

**Clic droit sur Load Test → Add → Listener :**

### 5.1 — View Results Tree (debug)
→ `View Results Tree`  
Cocher `Save as XML` → fichier `results_tree.xml`

### 5.2 — Summary Report
→ `Summary Report`  
Colonnes : Label, Samples, Average, Min, Max, Error%, Throughput

### 5.3 — Aggregate Report
→ `Aggregate Report`  
→ Filename : `aggregate_report.csv`

### 5.4 — Response Times Over Time
→ `jp@gc - Response Times Over Time` *(plugin JMeter Plugins Manager)*  
→ Filename : `response_times.csv`

### 5.5 — Active Threads Over Time
→ `jp@gc - Active Threads Over Time`

---

## Étape 6 — Installer les Plugins (recommandé)

1. Télécharger **JMeter Plugins Manager** : https://jmeter-plugins.org/install/Install/
2. Copier `jmeter-plugins-manager-X.X.jar` dans `lib/ext/`
3. Redémarrer JMeter
4. Aller dans **Options → Plugins Manager**
5. Installer : `jpgc - Standard Set`, `jpgc - Graphs Generator`

---

## Étape 7 — Configuration de charge recommandée

### Scénario 1 : Test de fumée (Smoke Test)

| Paramètre | Valeur |
|---|---|
| Threads | `1` |
| Ramp-up | `1s` |
| Duration | `60s` |
| Objectif | Vérifier que tout fonctionne sans erreur |

### Scénario 2 : Test de charge normal (Load Test)

| Paramètre | Valeur |
|---|---|
| Threads | `10` |
| Ramp-up | `30s` |
| Duration | `300s` |
| Objectif | Comportement sous charge normale |

### Scénario 3 : Test de stress (Stress Test)

| Paramètre | Valeur |
|---|---|
| Threads | `50` |
| Ramp-up | `120s` |
| Duration | `600s` |
| Objectif | Trouver le point de rupture |

### Scénario 4 : Test de pic (Spike Test)

| Paramètre | Valeur |
|---|---|
| Threads | `100` |
| Ramp-up | `5s` (montée brutale) |
| Duration | `60s` |
| Objectif | Comportement lors d'un pic de trafic |

---

## Étape 8 — Seuils de performance acceptables

| Endpoint | Temps moyen | 95e percentile | Taux d'erreur |
|---|---|---|---|
| `GET /candidate/matches` | < 500 ms | < 1 000 ms | < 1% |
| `GET /candidate/matches/{jobId}/compute` | < 15 s | < 25 s | < 5% |
| `POST /auth/login` | < 300 ms | < 800 ms | < 1% |

---

## Étape 9 — Lancer en mode non-graphique (CI/CD)

```bash
# Lancer le test et générer un rapport HTML
jmeter -n -t test_plan_get_score.jmx \
       -l results/results.jtl \
       -e -o results/html_report/ \
       -Jthreads=10 \
       -Jramp_up=30 \
       -Jduration=300 \
       -JJOB_ID=<uuid-du-job>

# Afficher le rapport
open results/html_report/index.html
```

---

## Étape 10 — Interpréter les résultats

### Métriques clés dans le Summary Report

| Colonne | Description | Seuil d'alerte |
|---|---|---|
| **Average** | Temps de réponse moyen | > 5 000 ms |
| **90th pct** | 90% des requêtes sous ce seuil | > 10 000 ms |
| **95th pct** | 95% des requêtes sous ce seuil | > 15 000 ms |
| **99th pct** | 99% des requêtes sous ce seuil | > 25 000 ms |
| **Error%** | % de requêtes en erreur | > 5% |
| **Throughput** | Requêtes par seconde | < 0.5 req/s = problème |

### Causes fréquentes d'erreurs

| Erreur | Cause probable | Solution |
|---|---|---|
| 401 Unauthorized | Token JWT expiré entre les requêtes | Réduire la durée du test ou re-login périodique |
| 503 Service Unavailable | Microservice Python surchargé | Augmenter les workers uvicorn |
| Connection refused | Backend Spring Boot pas démarré | Vérifier port 8080 |
| Timeout (> 30s) | Python en train de charger le modèle IA | Attendre que le modèle soit en mémoire avant le test |

---

## Annexe — Lancer plusieurs candidats (CSV Data Set)

Pour simuler plusieurs candidats différents, créer un fichier `candidats.csv` :

```csv
email,password
alice@test.com,Test@1234!
bob@test.com,Test@1234!
carol@test.com,Test@1234!
...
```

Dans JMeter :
**Clic droit sur Thread Group → Add → Config Element → CSV Data Set Config**

| Champ | Valeur |
|---|---|
| Filename | `candidats.csv` |
| Variable Names | `CANDIDATE_EMAIL,CANDIDATE_PASSWORD` |
| Delimiter | `,` |
| Recycle on EOF | `True` |
| Sharing mode | `Current thread group` |
