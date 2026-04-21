# MedTrack

**A secure, Dockerised, Kotlin-first Medicine Intake Tracker**
`com.joechrist.medtrack` · Ktor backend · Android (Jetpack Compose) · PostgreSQL · MinIO · Gotenberg

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Quick Start (Development)](#quick-start-development)
3. [Quick Start (Production)](#quick-start-production)
4. [Project Structure](#project-structure)
5. [API Reference](#api-reference)
6. [Security Model](#security-model)
7. [Data Anonymisation](#data-anonymisation)
8. [Android Features](#android-features)
9. [Architecture Decision Records](#architecture-decision-records)
10. [Environment Variables](#environment-variables)

---

## Architecture Overview

```
┌──────────────────────────────────────────────────┐
│  Android Client  (com.joechrist.medtrack)         │
│  Compose · Hilt · Retrofit · Room · AlarmManager │
└──────────────────┬───────────────────────────────┘
                   │ HTTPS + WSS (Bearer Firebase JWT)
                   ▼
┌──────────────────────────────────────────────────┐
│  Nginx  (SSL termination, rate limiting, WS)     │
└──────────────────┬───────────────────────────────┘
                   ▼
┌──────────────────────────────────────────────────┐
│  Ktor API  :8080                                 │
│  Firebase JWT verify → RBAC (DOCTOR/PATIENT)     │
└────┬──────────┬──────────┬──────────┬────────────┘
     ▼          ▼          ▼          ▼
PostgreSQL   MinIO     Gotenberg  Firebase
(primary DB) (S3)      (PDF)      (Auth+FCM)
```

All four backend services run in a single `docker-compose.yml`. No cloud infrastructure required except Firebase (free Spark plan).

---

## Quick Start (Development)

### Prerequisites
- Docker + Docker Compose v2
- JDK 21 (backend dev)
- Android Studio Hedgehog+
- Firebase project (free)

### Steps

```bash
git clone https://github.com/your-org/medtrack.git
cd medtrack
cp .env.example .env
# Edit .env: fill POSTGRES_PASSWORD, MINIO_ROOT_PASSWORD, FIREBASE_PROJECT_ID,
#            FIREBASE_SERVICE_ACCOUNT_KEY

mkdir -p backend/resources/firebase
cp ~/Downloads/service-account.json backend/resources/firebase/service-account.json

docker compose --profile dev up --build
```

| Service       | URL                       |
|---------------|---------------------------|
| Ktor API      | http://localhost:8080     |
| MinIO Console | http://localhost:9001     |
| Gotenberg     | http://localhost:3000     |
| pgAdmin       | http://localhost:5050     |

**Android**: place `google-services.json` in `android/app/`, set `ANDROID_API_BASE_URL=http://10.0.2.2:8080` in `local.properties`.

---

## Quick Start (Production)

```bash
echo "NGINX_HOST=api.yourdomain.com" >> .env
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

The production overlay adds Nginx with TLS, auto-renewing Let's Encrypt certs, removes public ports from internal services, and sets resource limits.

---

## Project Structure

```
medtrack/
├── .github/workflows/ci.yml       # CI: lint → test → Docker → APK
├── docker-compose.yml             # Dev: 4 services
├── docker-compose.prod.yml        # Prod overlay: Nginx + limits
├── .env.example
├── db/init/
│   ├── 01_schema.sql              # Full schema (auto-runs on boot)
│   └── 02_seed.sql                # Sample medications
├── infra/nginx/
│   ├── nginx.conf                 # SSL, rate limiting, WS proxy
│   └── proxy_params
├── backend/                       # Ktor (Kotlin/JVM 21)
│   ├── plugins/Security.kt        # Firebase JWT + RBAC
│   ├── plugins/RateLimiting.kt    # 60 req/min API, 10 req/min auth
│   ├── service/PrescriptionService.kt
│   ├── service/PdfService.kt      # HTML → PDF via Gotenberg
│   ├── service/StorageService.kt  # MinIO S3
│   ├── service/MedicationSearchService.kt  # openFDA + cache
│   └── routes/                    # Auth · Users · Prescriptions · Chat
└── android/                       # Jetpack Compose
    ├── data/local/                # Room DB (5 tables + chat)
    ├── data/remote/               # Retrofit + AuthInterceptor
    ├── data/repository/           # SyncRepository · ChatRepository · AlarmScheduler
    ├── alarm/MedicationAlarmReceiver.kt   # Doze-safe exact alarms
    ├── worker/SyncWorker.kt       # WorkManager 15-min sync
    └── ui/doctor · patient · chat · auth
```

---

## API Reference

Base URL: `/api/v1` · Auth: `Authorization: Bearer <Firebase ID Token>`

### Auth
| Method | Path | Role | Description |
|--------|------|------|-------------|
| POST | `/auth/register` | Any | Register after Firebase sign-up |
| GET | `/auth/me` | Any | Own profile + role |

### Prescriptions
| Method | Path | Role | Description |
|--------|------|------|-------------|
| POST | `/prescriptions` | Doctor | Create → PDF → MinIO |
| GET | `/prescriptions/{id}` | Gated | PHI gated by role |
| GET | `/prescriptions/{id}/pdf` | Gated | 15-min signed URL |
| GET | `/prescriptions/patient/{id}` | Gated | Patient list |
| PATCH | `/prescriptions/{id}/status` | Doctor | Update status |

### Medications
| Method | Path | Role | Description |
|--------|------|------|-------------|
| GET | `/medications/search?q=` | Any | Local cache → openFDA fallback |

### Intake Logs
| Method | Path | Role | Description |
|--------|------|------|-------------|
| GET | `/intake-logs/patient/{id}` | Gated | Log history |
| PATCH | `/intake-logs/{id}/taken` | Patient | Mark taken |
| PATCH | `/intake-logs/{id}/missed` | Any | Mark missed |

### Chat
| Protocol | Path | Description |
|----------|------|-------------|
| WSS | `/chat/ws/{roomId}?token=<jwt>` | Real-time messages |
| GET | `/chat/rooms/{roomId}/history` | Message history |

### Health
| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Liveness |
| GET | `/health/ready` | Readiness (DB + services) |
| GET | `/health/version` | Build info |

---

## Security Model

- **Authentication**: Firebase RS256 JWT, verified server-side with Firebase Admin SDK. Token revocation checked on every request.
- **RBAC**: Three roles (ADMIN / DOCTOR / PATIENT) resolved from the DB after token verification. Enforced at Ktor route level via `doctorOnly {}`, `patientOnly {}`, `anyAuthenticated {}` DSL helpers.
- **Transport**: TLS 1.2/1.3, HSTS, X-Frame-Options DENY, X-Content-Type-Options nosniff.
- **Rate limiting**: Nginx (60 req/min API, 10 req/min auth) + Ktor middleware (defence in depth).

---

## Data Anonymisation

| Context | Name shown | Who sees it |
|---------|-----------|-------------|
| Doctor's patient detail | Full name | DOCTOR / ADMIN |
| Patient's own record | Full name | Self |
| Shared panels / lists | `J*** D**` | Anyone |
| `anonymised_audit_logs` | `PT-7F3A` alias only | System |

PHI masking is enforced at the repository layer (`toDto(callerRole)`) — not in the UI.

---

## Android Features

| Feature | Implementation |
|---------|---------------|
| Auth | Firebase Email + Google Sign-In · DataStore session cache |
| Offline | Room DB · optimistic writes · SyncQueue retry buffer |
| Alarms | `setExactAndAllowWhileIdle` (API 26+) · `USE_EXACT_ALARM` (API 33+) |
| Notifications | Heads-up · "Taken ✓" + "Snooze 15min" action buttons |
| Doctor UI | Stats cards · anonymised patient list · 5-step Rx wizard |
| Patient UI | Schedule timeline · compliance ring · optimistic mark-taken |
| Chat | OkHttp WS · exponential backoff · Room persistence · PENDING/SENT/FAILED |

---

## Architecture Decision Records

**ADR-001 — Firebase Auth**: Eliminates password storage, provides Google Sign-In, handles token revocation. Server never sees plaintext credentials.

**ADR-002 — Anonymisation at repository layer**: PHI masking in `toDto(callerRole)` prevents accidental leaks from new screens that forget to apply masking.

**ADR-003 — Deterministic IntakeLog IDs**: `"{rxId}_{date}_{HHmm}"` makes upserts idempotent across re-schedules. Random UUIDs would create duplicate alarm rows on every reboot.

**ADR-004 — Gotenberg for PDFs**: HTML/CSS templates are far easier to maintain than programmatic PDF APIs. Runs as a Docker sidecar; no JVM PDF library or licensing required.

**ADR-005 — WebSocket token in query param**: Browsers can't set `Authorization` headers on WS connections. Query-param is the standard workaround; token is fetched fresh before each `connect()`.

**ADR-006 — Offline-first optimistic writes**: Medication adherence is time-critical. A network error must never prevent a patient from logging a dose. `SyncWorker` drains the queue silently when connectivity returns.

---

## Environment Variables

See `.env.example` for the full annotated reference.

| Variable | Required | Notes |
|----------|----------|-------|
| `FIREBASE_PROJECT_ID` | ✅ | Firebase Console → Project Settings |
| `FIREBASE_SERVICE_ACCOUNT_KEY` | ✅ | Path to JSON or base64 string |
| `POSTGRES_PASSWORD` | ✅ | Strong random value |
| `MINIO_ROOT_PASSWORD` | ✅ | Strong random value |
| `APP_SECRET` | ✅ | 64-char random string |
| `NGINX_HOST` | Prod | Domain for TLS cert |
| `OPENFDA_API_KEY` | Optional | Raises limit from 1k → 120k req/day |
| `ANDROID_API_BASE_URL` | Build | `http://10.0.2.2:8080` for emulator |

---

## License

MIT — see `LICENSE`.

---

## Firebase Setup (google-services.json)

The real `google-services.json` is included at `android/app/google-services.json`.

**Firebase Project:** `medtrack-8b6a1`  
**Project Number:** `949223062344`  
**Android App ID:** `1:949223062344:android:03031e320cbf8deaba517d`  
**Package:** `com.joechrist.medtrack`

### Before building, complete these steps in Firebase Console:

1. **Enable Authentication providers:**
   - Email/Password: Authentication → Sign-in method → Email/Password → Enable
   - Google Sign-In: Authentication → Sign-in method → Google → Enable
     - After enabling Google, re-download `google-services.json` to get the `oauth_client` entry
     - Update `strings.xml` → `default_web_client_id` with the new Web Client ID

2. **Add SHA-1 fingerprints** (Project Settings → Your apps → Android):
   ```bash
   # Debug key (emulator/dev builds)
   keytool -list -v -keystore ~/.android/debug.keystore \
     -alias androiddebugkey -storepass android -keypass android
   ```

3. **Enable Cloud Messaging** (for FCM push notifications):
   - Firebase Console → Cloud Messaging → ensure it's enabled

4. **Backend service account:** Download from Project Settings → Service Accounts → Generate new private key
   and place at `backend/resources/firebase/service-account.json`
