# Sologix Attendance App

A production-grade attendance and field-workforce management system built with **Native Android (Kotlin + Jetpack Compose)** and a **Node.js (Plain JavaScript) + MySQL** backend on Google Cloud Platform (GCP).

---

## Architecture Overview

```
+---------------------------------------------------------------------------------------+
|                                    ANDROID CLIENT                                     |
|  Compose UI (StateFlow) <─── Room Flow <─── Local SQLite Database (Local-first writes) |
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
|  Nginx (Reverse Proxy & TLS) ───► Express.js (Port 3000) ───► MySQL 8.0 (127.0.0.1)    |
|                                         │                                             |
|                                         ▼ (Verify OTP token at login)                 |
|                                Firebase Admin SDK                                     |
+---------------------------------------------------------------------------------------+
```

---

## Key Guarantees & Features

1. **Local-First & Offline-First Sync:**
   - Every mutation (check-in, check-out, GPS points, tasks, visits) is written locally to Room SQLite immediately with `syncState = PENDING`.
   - The UI reflects mutations instantly and never blocks on network connectivity.
2. **Stable Client-Generated IDs:**
   - Client generates standard UUIDs (`VARCHAR(36)`) for every entity, ensuring identical identity across client and server.
3. **Split Idempotency Keys:**
   - Attendance check-in and check-out use separate idempotency keys (`check_in_operation_id` and `check_out_operation_id`) with MySQL `UNIQUE` constraints and conditional update checks, preventing duplicate writes on retries.
4. **Crash-Safe Queue Recovery:**
   - On app startup, any queued mutation stuck in `IN_PROGRESS` from an interrupted process or power-loss is automatically reset to `FAILED` and rescheduled.
5. **Foreground Shift Tracking (Option A):**
   - Active route tracking uses an Android Foreground Service declaring `foregroundServiceType="location"` with persistent user notification. Avoids Play Store background location review while ensuring uninterrupted GPS logging during shifts.
6. **Hardware Mock Location Detection:**
   - Dual-branch SDK-guarded mock detection (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` checking `location.isMock` vs `location.isFromMockProvider`).
7. **Hydration Merge Guard:**
   - Local records awaiting sync are never overwritten by stale server snapshots during hydration or pull-to-refresh.

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
