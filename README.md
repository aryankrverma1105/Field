# Sologix Attendance App

A production-grade attendance and field-workforce management system built with **Native Android (Kotlin + Jetpack Compose)** and a **Node.js (Plain JavaScript) + MySQL** backend on Google Cloud Platform (GCP).

---

## Architecture Overview

```
+---------------------------------------------------------------------------------------+
|                                    ANDROID CLIENT                                     |
|  Compose UI (StateFlow) <─── Room Flow <─── Local SQLite Database (Local-first writes)|
|                                                    │                                  |
|                                                    ▼                                  |
|                                           sync_queue (Room)                           |
|                                                    │                                  |
|                                                    ▼                                  |
|                                         SyncWorker (WorkManager)                      |
|                                                    │                                  |
|                                                    ▼                                  |
|                                            Retrofit / OkHttp                          |
+----------------------------------------------------┼----------------------------------+
                                                     │ HTTPS (JWT Auth)
                                                     ▼
+---------------------------------------------------------------------------------------+
|                                  GCP BACKEND (Ubuntu)                                 |
|  Nginx (Reverse Proxy & TLS) ───► Express.js (Port 3000) ───► MySQL 8.0 (127.0.0.1)   |
|                                         │                                             |
|                                         ▼ (Verify OTP token at login)                 |
|                                Firebase Admin SDK                                     |
+---------------------------------------------------------------------------------------+
```

---

## Key Guarantees & Implementation Status

| Subsystem / Guarantee | Status | Notes / Verification |
| :--- | :--- | :--- |
| **Split Idempotency Keys** (Check-In & Check-Out) | **Implemented and tested** | Split operation IDs on MySQL schema (`check_in_operation_id`, `check_out_operation_id`) and Room entities. Verified via real DB HTTP tests and Room unit tests. |
| **Backend Express Server & JWT Middleware** | **Implemented and tested** | `src/server.js` running with HS256 JWT enforcement, 401 on missing/expired/`alg:none`, real HTTP endpoints. Verified via `node --test`. |
| **Backend DB Security & Startup Env Validation** | **Implemented and tested** | Hardcoded passwords completely removed. Process exits with code 1 if required env vars (`DATABASE_USER, DATABASE_PASSWORD, DATABASE_NAME, JWT_SECRET`) are missing or `JWT_SECRET` < 32 chars. `DATABASE_HOST` defaults to `127.0.0.1`. |
| **Stale Row Protection on Check-Out** | **Implemented and tested** | Targeted SQL `WHERE id=? AND user_id=? AND check_in_at IS NOT NULL AND check_out_operation_id IS NULL`. Prevents closing 2-day-old sessions. |
| **Primary-Key Collision Protection on Check-In** | **Implemented and tested** | Returns HTTP 409 with existing record instead of `undefined` on PK conflict. |
| **Server-Controlled Geofence Status** | **Implemented and tested** | Client geofence input is ignored; server computes Haversine distance against user's assigned sites in `user_sites`/`sites` (`INSIDE` / `OUTSIDE` / `UNKNOWN`). |
| **Hilt DI & Retrofit Network Pipeline** | **Implemented and tested** | `TokenStore` (`EncryptedSharedPreferences`), `AuthInterceptor`, `OkHttpClient`, `Retrofit`, and `ApiService`. Tested with `MockWebServer`. |
| **Atomic Claim in SyncWorker** | **Implemented and tested** | Atomic `UPDATE sync_queue SET status='IN_PROGRESS' WHERE operation_id=? AND status IN ('PENDING','FAILED')` prevents dual-worker dispatch races. |
| **Dead-Letter Queue & Status Classification** | **Implemented and tested** | `QueueStatus.DEAD` with `last_error` column. 400/404/409 or 5 consecutive failures transition to DEAD. Repository blocks reviving DEAD items with `AttendanceWriteResult.BlockedByDeadOperation` unless manually retried. |
| **Per-Entity Dispatch Ordering** | **Implemented and tested** | Check-out operations wait until prior operations for the same `entity_id` are SYNCED. Entity failure halts subsequent operations for that entity while allowing other entities to proceed. |
| **Room Schema Export & Migration (1 → 2)** | **Implemented and tested** | `fallbackToDestructiveMigration()` removed. `exportSchema = true` with committed schema JSONs and real `Migration(1, 2)`. |
| **Kotlin Hydration Merge Guard & Data Flow** | **Implemented and tested** | `HydrationMergeGuard.merge` wired into `AttendanceRepository.hydrate()` calling `GET /api/attendance/history`. Called on app start and manual UI trigger. Verified with Robolectric tests. |
| **Foreground Shift Tracking Service (Option A)** | **Implemented and tested** | Foreground Service (`foregroundServiceType="location"`) with runtime permission flow, persistent notification, `START_STICKY` restart handling, and atomic `LocationTrackingSessionStore` (`SharedPreferences` accepted for synchronous non-blocking access in `onStartCommand()`). |
| **Mock Location Detector** | **Implemented and tested** | Hardware mock detection with API-level branching tested in `MockLocationDetectorTest`. |
| **Firebase Phone Auth Flow & UI** | **In progress** | Backend JWT issuance & authentication pipeline. |
| **CameraX Selfie Capture UI** | **Not started** | Phase 2 scope. |
| **Server-Side Geofence Polygon Evaluation** | **Implemented and tested** | Multi-site Haversine distance evaluation in `attendanceService.js`. |

---

## Security & Environment Configuration

### Required Environment Variables (Backend)
The backend service enforces presence and validity of the following variables in `src/config/env.js` on startup:
- `DATABASE_USER`: MySQL database username.
- `DATABASE_PASSWORD`: MySQL database user password.
- `DATABASE_NAME`: Target database name.
- `JWT_SECRET`: Signing key for application JSON Web Tokens (must be $\ge$ 32 characters).

The server defaults `DATABASE_HOST` to `127.0.0.1`, `DATABASE_PORT` to `3306`, and `PORT` to `3000`. If any of the four required variables are missing or empty, or if `JWT_SECRET` is less than 32 characters, the application terminates immediately with exit code 1.

### Storage Architecture Decision: `LocationTrackingSessionStore`
`LocationTrackingSessionStore` utilizes Android `SharedPreferences` instead of Jetpack DataStore. This architectural choice is intentional and accepted: `LocationTrackingService` runs as a high-reliability foreground service that is restarted with `START_STICKY` (null intent) upon process termination. `onStartCommand()` requires immediate, synchronous, zero-latency access to the active session state without dispatching coroutines or invoking `runBlocking` on the main application thread.

---

## Project Structure

```
.
├── android/                   # Native Android Project
│   ├── app/
│   │   ├── src/main/java/com/sologix/attendance/
│   │   │   ├── data/local/    # Room Database, Entities & DAOs
│   │   │   ├── location/      # Mock detector & location helpers
│   │   │   ├── receiver/      # Boot completed receiver
│   │   │   ├── service/       # Foreground location service
│   │   │   ├── sync/          # WorkManager SyncWorker & SyncManager
│   │   │   └── ui/            # Jetpack Compose UI & ViewModels
│   │   └── src/test/          # Unit tests (Mock detector, Queue crash-safety)
│   └── build.gradle.kts
└── backend/                   # Node.js + Express + MySQL Backend
    ├── src/
    │   ├── db/                # MySQL connection pool & migrations
    │   └── services/          # Idempotent business services
    └── tests/                 # Automated unit tests for idempotency & hydration
```

---

## Quickstart

### Backend Setup
1. `cd backend`
2. `npm install`
3. Copy `.env.example` to `.env` and fill in your MySQL credentials.
4. Run migrations: `npm run migrate`
5. Run tests: `npm test`
6. Start dev server: `npm run dev`

### Android Setup
1. Open the `android/` directory in Android Studio or Antigravity IDE.
2. Add your `google-services.json` into `android/app/`.
3. Build and test: `./gradlew testDebugUnitTest`
