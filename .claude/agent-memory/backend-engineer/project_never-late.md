---
name: project_never-late
description: Never Late Again's backend sub-project — stack, layout, conventions, and where the contract lives.
metadata:
  type: project
---

Never Late Again (`/home/aritz/projects/never-late`) is a monorepo teaching project: an Android
app (`app/`) plus, as of feature 11 (remote DB + offline-first sync), a sibling Kotlin/Ktor
backend at `backend/`. The backend is a **separate Gradle build** (own `settings.gradle.kts`,
`build.gradle.kts`, `gradle/libs.versions.toml`, and its own copy of the Gradle wrapper — same
Gradle version, 8.13, as the root app build, but not wired into the root `settings.gradle.kts`).

**Why this matters:** the two builds intentionally don't share a Kotlin version. The Android app
pins Kotlin 2.1.0 (root `gradle/libs.versions.toml`); the backend pins Kotlin 2.3.21, because Ktor
3.5.1's published artifacts require a compiler new enough to read their metadata format (2.1.0
compilers on 2.3.x artifacts silently gets `IllegalArgumentException: source must not be null`
FIR-analysis crashes, then a cascade of "Module was compiled with an incompatible version of
Kotlin" once you fix the crash — check the Kotlin version whenever bumping Ktor here). Don't
"reconcile" the two catalogs to match — they're independent on purpose (see the code comment in
`backend/gradle/libs.versions.toml`).

**Stack chosen (2026-07-06, feature 11):** Ktor (Netty engine) + kotlinx.serialization + plain JDBC
(HikariCP pool, no ORM — Exposed was considered but plain JDBC was chosen for a two-table schema
to keep the concept count low) + `org.mindrot:jbcrypt` for password hashing + `com.auth0:java-jwt`
for stateless JWT (via Ktor's `Authentication`/`jwt` plugin). Schema is `CREATE TABLE IF NOT
EXISTS` run at startup (no migration framework — too small a schema to need one yet).

**Testing pattern:** the repository interfaces (`UserRepository`, `TaskRepository`) have both a
`Postgres*` implementation (production, JDBC) and an `InMemory*` implementation (tests — plain
`ConcurrentHashMap`, no Docker/DB needed). This deliberately mirrors the client app's
`TaskRepository` seam pattern from CLAUDE.md. Tests use Ktor's `testApplication` + JUnit 5; see
`backend/src/test/kotlin/.../TestSupport.kt` for the shared `testConfig()` / `jsonClient()`
helpers.

**The API contract** (`docs/api/contract.md`) is the single source of truth for endpoint shapes —
read it fully before touching any route. Two contract details that are easy to get wrong:
1. The server is authoritative on `updatedAt` for every write (create/update/delete all stamp
   `System.currentTimeMillis()`), even though `POST`/`PATCH` request bodies also carry a
   client-supplied `updatedAt` — that field is *only* used as the last-write-wins comparison input
   on PATCH, never copied into storage.
2. `PATCH /tasks/{id}` must distinguish "field omitted" (leave unchanged) from "field present with
   value `null`" (clear it) for nullable fields (`estimatedDurationMillis`, `deadline`). A plain
   `@Serializable` data class can't express that — see `common/PatchValue.kt` and how
   `tasks/TaskRoutes.kt` reads the raw `JsonObject` instead of decoding straight into a typed
   request model.

**docker-compose.yml** binds host port 8080 (documented, matches `10.0.2.2:8080` from the Android
emulator per the contract) — if 8080 is already taken locally by an unrelated project's container
when smoke-testing, remap only for that local test and revert before finishing; don't change the
committed default.

See also [[gradle-shadow-plugin-version-pin]] for the shadow/Gradle compatibility gotcha hit while
setting this up.
