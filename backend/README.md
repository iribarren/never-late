# Never Late — Backend

Kotlin + Ktor + Postgres service that owns user accounts and tasks for the Never Late Again app
(feature 11 — remote DB + offline-first sync). This is an independent Gradle project: it has its
own wrapper and does not share a build with the Android app in `../app`.

**Endpoints, request/response shapes, auth, and error format are documented in
[`../docs/api/contract.md`](../docs/api/contract.md) — that file is the single source of truth
for both this backend and the Android client, and is not repeated here.**

## Stack

- Kotlin + [Ktor](https://ktor.io/) (Netty engine), `kotlinx.serialization` for JSON.
- Postgres, accessed via plain JDBC + a HikariCP connection pool (no ORM — see
  `src/main/kotlin/com/neverlate/backend/db/Database.kt` for why).
- Stateless JWT auth (`com.auth0:java-jwt` + Ktor's `Authentication`/`jwt` plugin), passwords
  hashed with bcrypt (`jbcrypt`).
- Dependency versions live in `gradle/libs.versions.toml`, mirroring the convention used by the
  app's own version catalog.

## Run locally

```bash
cd backend
cp .env.example .env   # then fill in real values (a JWT secret, DB credentials)
docker compose up --build
```

This starts two containers: `db` (Postgres) and `backend` (this service, built from the
`Dockerfile`), reachable at `http://localhost:8080` from the host or `http://10.0.2.2:8080` from
the Android emulator.

To build/test without Docker (the test suite uses in-memory repository fakes, not a real
database — see `src/test/kotlin`):

```bash
./gradlew build
./gradlew test
```

## Smoke sequence

With the stack running (`docker compose up`), verify the core flow end-to-end:

```bash
# 1. Register — returns a token + user
curl -s -X POST http://localhost:8080/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo@example.com","password":"password123"}'

# 2. Create a task (paste the token from step 1)
curl -s -X POST http://localhost:8080/tasks \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <token>' \
  -d '{"clientRef":"smoke-test-1","title":"Buy milk","estimatedDurationMillis":1800000,"updatedAt":0}'

# 3. Pull the full task list
curl -s http://localhost:8080/tasks?since=0 -H 'Authorization: Bearer <token>'

# 4. Log back in with the same credentials
curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo@example.com","password":"password123"}'
```

Each step should succeed with the status codes documented in `../docs/api/contract.md` §6.

## Configuration

All configuration is read from environment variables at startup (`Config.fromEnv()`) — see
`.env.example` for the full list with comments. Missing secrets (`JWT_SECRET`, DB credentials)
fail startup immediately rather than falling back to an insecure default.
