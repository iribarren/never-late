# Never Late Again — Workspace

<!-- Installed by setup-claude.sh — project type: mobile · agents: project-manager-docs, qa-engineer, devops-security-engineer, mobile-engineer -->

## Overview

Native **Android** app (Kotlin + Jetpack Compose) that helps people with ADD/ADHD manage their
time and the tasks they need to get done.

This is also a **teaching project**: the app is built as a progressive tutorial to learn Kotlin
and Android development. Each new feature must introduce new concepts, from the basics up. See
**Tutorial Methodology** below — it is binding for every feature.

The app is **client + backend** as of feature 11: the Android app is an **offline-first client** of a
small **Kotlin/Ktor + Postgres backend** that owns user accounts and tasks. Data is still cached and
fully usable on-device (Room), but the backend is the source of truth for synced data. The backend
runs locally via `docker compose` for the tutorial (cloud hosting is out of scope). Earlier features
(01–10) were local-only; that history is preserved in the tutorial lessons.

## Structure

Monorepo: the Android app (`app/`) plus a sibling backend service (`backend/`), sharing one API
contract.

```
never-late/
├─ settings.gradle.kts          # Modules + repositories (Android app)
├─ build.gradle.kts             # Root: declares plugins (apply false)
├─ gradle.properties            # Gradle/AndroidX flags
├─ gradle/
│  ├─ libs.versions.toml        # Version catalog (single source of truth for versions)
│  └─ wrapper/                  # Gradle wrapper (pins Gradle 8.13)
├─ gradlew / gradlew.bat        # Wrapper launchers — always build via these
├─ app/
│  ├─ build.gradle.kts          # Android app module config + dependencies
│  └─ src/
│     ├─ main/
│     │  ├─ AndroidManifest.xml
│     │  ├─ java/com/neverlate/
│     │  │  ├─ MainActivity.kt        # Single Activity, hosts the Compose UI
│     │  │  └─ ui/theme/              # Compose theme: Color / Theme / Type
│     │  └─ res/                      # strings, themes, launcher icon
│     ├─ test/                        # Local JVM unit tests
│     └─ androidTest/                 # Instrumented / Compose UI tests
├─ backend/                     # Kotlin + Ktor + Postgres service (feature 11) — its own Gradle build
│  ├─ build.gradle.kts          # Backend module config + dependencies (separate from the app)
│  ├─ docker-compose.yml        # Backend + Postgres for local dev
│  ├─ README.md                 # Run + smoke instructions (does NOT re-list endpoints; see contract)
│  └─ src/                      # Ktor app: auth (JWT), tasks REST, persistence
├─ tutorial/                    # Spanish lessons, one per feature (see Tutorial Methodology)
└─ docs/
   ├─ api/                      # API contract — source of truth for client + server (feature 11)
   ├─ prompts/                  # Ready-to-paste prompts to start each feature in a new session
   ├─ specs/                    # Feature specs (project-manager-docs)
   └─ articles-api/             # Static JSON served over HTTPS via GitHub raw (feature 10)
```

As the app grows, feature code lives under `app/src/main/java/com/neverlate/` in packages such as
`ui/screens`, `ui/<feature>`, `data` (DataStore/Room), and `domain`. Current feature packages of
note: `ui/widget` (feature 05, home-screen widget), `ui/notification` (feature 06, lock-screen
notification + foreground service; feature 09 also adds the reminder scheduler + receivers here),
`ui/settings` (feature 07, Settings screen + light/dark/system theme preference persisted via the
shared `user_prefs` DataStore and applied in `NeverLateTheme`; feature 09 adds the reminders on/off
+ lead-time controls), and `domain/tasks` (rules shared across surfaces, e.g. `pendingRowsFor` and
feature 09's pure reminder-scheduling logic in `ReminderPlanning.kt`).

**Reminders** (feature 09): schedules a one-shot *alerting* local notification a configurable lead
time before a task's `deadline`, firing even with the app closed, and reschedules after reboot. Pure
scheduling logic (`reminderTimeFor`, `isReminderInFuture`, `remindersToSchedule`) lives in
`domain/tasks/ReminderPlanning.kt`; the Android shells live in `ui/notification`:
`AlarmManagerReminderScheduler` (exact `setExactAndAllowWhileIdle`, graceful fallback to inexact when
`canScheduleExactAlarms()` is false), `ReminderReceiver` (posts the notification, `exported=false`),
`ReminderNotificationHelper` (a **second**, alerting `IMPORTANCE_HIGH` channel `task_reminders`,
distinct from feature 06's silent `tasks_pending`), `BootReceiver` + `BootRescheduleWorker`
(reschedule on `BOOT_COMPLETED`, delegated to WorkManager), and `ReminderSchedulingRepository` (a
second `TaskRepository` decorator, composed with `TaskSurfacesRefreshingRepository` in `MainActivity`,
that (re)schedules/cancels a task's alarm on save/delete). Reminder prefs (`remindersEnabled`,
`reminderLeadMinutes`) are stored in the same `user_prefs` DataStore.

**Localization** (feature 08, i18n): all user-facing text lives in string resources.
`res/values/strings.xml` is the Spanish base/fallback; `res/values-en/strings.xml` is the English
variant (selected by device language). Counts use `<plurals>` (`getQuantityString` /
`pluralStringResource`); numbers/dates are formatted per device `Locale` via `NumberFormat` /
`java.time` `DateTimeFormatter` (see `data/tasks/TaskTiming.kt`, where display formatting is
locale-aware and the deadline input round-trip is pinned to `Locale.ROOT`). `java.time` on
`minSdk = 24` is enabled by **core library desugaring** (`isCoreLibraryDesugaringEnabled` +
`coreLibraryDesugaring(libs.desugar.jdk.libs)`).

**Permissions** (declared in `AndroidManifest.xml`): `POST_NOTIFICATIONS` (feature 06; runtime
permission on Android 13+, requested from Compose), plus `FOREGROUND_SERVICE` /
`FOREGROUND_SERVICE_SPECIAL_USE` for the notification's foreground service
(`TasksNotificationService`, `foregroundServiceType="specialUse"`). Feature 09 adds
`SCHEDULE_EXACT_ALARM` (exact alarms on API 31+, checked at runtime via `canScheduleExactAlarms()`
with graceful fallback to inexact; `USE_EXACT_ALARM` is deliberately **not** declared) and
`RECEIVE_BOOT_COMPLETED` (reschedule reminders after reboot), plus two `<receiver>`s: `ReminderReceiver`
(`exported="false"`) and `BootReceiver` (`exported="true"`, `BOOT_COMPLETED` filter). Feature 10 adds
`INTERNET` (a normal permission, no runtime request) for the articles API. Feature 11 adds
`ACCESS_NETWORK_STATE` (also a normal permission, no runtime request) for the sync engine's
connectivity-aware WorkManager job.

**Articles from a remote API** (feature 10): replaces feature 03's bundled `assets/articles.json`
with a real network fetch. `data/articles/` gains `ArticlesApi` + `ArticlesNetwork` (Retrofit +
OkHttp, `HttpLoggingInterceptor` gated to debug builds via `BuildConfig.DEBUG`, deserializing with
the existing `kotlinx.serialization` through a Retrofit converter), `ArticleDto` (the wire shape,
deliberately different from the `Article` domain model — `article_id`/`content`, no `summary` —
mapped by `ArticleDto.toDomain()`), and `ArticleEntity` + `ArticleDao` (the Room cache). The remote
source is a static JSON file at `docs/articles-api/articles.json`, served over HTTPS via GitHub
raw once pushed to `master`. `CachingArticleRepository` implements `ArticleRepository` with Room as
the **single source of truth** (`getArticles()`/`getArticleById()` always read the cache) and adds
an additive `refresh(): RefreshResult` that the ViewModels use for a *stale-while-revalidate*
strategy (show the cache immediately, then update it from the network) plus pull-to-refresh and a
retry action on failure. `ArticleEntity` lives in the same `NeverLateDatabase` as `Task`, which
bumps `version` 1 → 2 — per that database's existing `fallbackToDestructiveMigration` policy, this
wipes tasks on devices that already have data, accepted pre-release the same way earlier schema
changes were.

**Remote DB + offline-first sync** (feature 11): the app gains a real backend (`backend/`, Kotlin +
Ktor + Postgres) that owns accounts and tasks; the Android app becomes an **offline-first client**.
Basic email/password **auth** issues a stateless **JWT** (no refresh in v1), attached to every task
call via an OkHttp interceptor and stored in **Keystore-backed encrypted storage** (not the plaintext
`user_prefs` DataStore). Room stays the **local single source of truth**; each mutation writes the
task row **and** a `task_outbox` change row in the same transaction. A sync engine does **push** (replay
the outbox — idempotent creates keyed by `clientRef`, tombstones for deletes) and **pull**
(`GET /tasks?since=`), reconciling with **last-write-wins by `updatedAt`** (delete wins over edit); the
pure reconciliation lives in `domain/sync/` (JVM-testable, like `ReminderPlanning.kt`). Tasks gain sync
metadata (`serverId`, `updatedAt`, `syncState`, `deleted`), bumping `NeverLateDatabase` **2 → 3**
(destructive migration per project precedent — the cache repopulates from the backend after login). The
`TaskRepository` seam is preserved: sync/auth enter behind it. See **API Contract** below.

The local dev backend is plaintext HTTP (`http://10.0.2.2:8080`), which `targetSdk 36` blocks by
default; a **debug-build-only** network security config (`app/src/debug/res/xml/network_security_config.xml`,
wired via `app/src/debug/AndroidManifest.xml`) scopes the cleartext exception to `10.0.2.2`/`localhost`
only — release builds get no exception at all. **Before any real deployment, the backend must be served
over HTTPS** and this debug-only exception must not be widened or copied into the release manifest.

## API Contract

The app now talks to a backend, so the HTTP contract between client (`app/`) and server (`backend/`)
is a **first-class, committed artifact** and the **single source of truth** for both sides: it is
authored/changed **first**, and client and server follow. Do not let the client and server drift from
it, and do not re-document endpoints elsewhere (the backend `README.md` points to the contract rather
than re-listing routes).

- **Contract:** [`docs/api/contract.md`](docs/api/contract.md) — endpoints (`/auth/register`,
  `/auth/login`, `/tasks` CRUD + `GET ?since=` for pull), the `TaskDto` wire shape (deliberately
  distinct from the Room `Task` entity), the JSON error envelope, auth (Bearer JWT), and sync
  semantics.
- **Rule:** any change to request/response shapes, status codes, or auth updates `docs/api/contract.md`
  in the same change, and both sides are reconciled to it. Sensitive logic (ownership checks,
  validation, authority over `id`/`updatedAt`) lives on the **server**; the client is untrusted.

## Development

- **JDK:** 21 (system) or the Android Studio bundled JBR at `~/android-studio/jbr`.
- **Android SDK:** `~/Android/Sdk` (configured in `local.properties` via `sdk.dir`).
- **SDK config:** `compileSdk = 36`, `targetSdk = 36`, `minSdk = 24`.
- Extra SDK packages/licenses: `~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager`.

```bash
# Build the debug APK
./gradlew :app:assembleDebug

# Install the debug build on a running device/emulator
./gradlew :app:installDebug

# Unit tests (JVM) and instrumented tests (needs a running emulator)
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest

# Launch the installed app
adb shell am start -n com.neverlate/.MainActivity
```

Alternatively, open the project in **Android Studio** (`~/android-studio/bin/studio.sh`) and use
the Run button.

| Target | How to run |
|--------|-----------|
| Android emulator | `~/Android/Sdk/emulator/emulator -avd Nexus_5X_API_29_x86 &` then `adb wait-for-device` |

### Backend (feature 11)

The backend is a separate Gradle project under `backend/` with its own `docker compose` (backend +
Postgres). Secrets (JWT signing key, DB credentials) come from **environment variables** — never
committed. See `backend/README.md` for the authoritative run/smoke steps.

```bash
# Start the backend + Postgres for local dev (from backend/)
cd backend && docker compose up --build

# The emulator reaches the host backend at http://10.0.2.2:8080 (see docs/api/contract.md)
```

#### Testing on a physical device

The app's backend base URL is a `BuildConfig` field (`BuildConfig.BACKEND_BASE_URL`, wired in
`app/build.gradle.kts` and read by `BackendNetwork.DEFAULT_BACKEND_BASE_URL`) fed from a
`neverlate.backendBaseUrl` property in **`local.properties`** (git-ignored — never put a personal
IP/URL in a tracked file). With no property set it defaults to `http://10.0.2.2:8080/`, so the
emulator flow needs no configuration at all.

To run the debug app on a **physical device**, add one line to your own `local.properties` (create
the file if you don't have one) and pick one of:

- **Recommended — USB / `adb reverse`:** connect the phone via USB, then:

  ```bash
  adb reverse tcp:8080 tcp:8080
  ```

  and set:

  ```properties
  neverlate.backendBaseUrl=http://localhost:8080/
  ```

  `localhost` is already in the debug-only cleartext allowlist
  (`app/src/debug/res/xml/network_security_config.xml`), so **no manifest/config change is
  needed** for this path.

- **Alternative — Wi-Fi / LAN IP:** set your PC's LAN IP instead:

  ```properties
  neverlate.backendBaseUrl=http://192.168.x.x:8080/
  ```

  This requires: the backend listening on `0.0.0.0` (not just `localhost`), port 8080 open on the
  host firewall, and the phone on the **same Wi-Fi network**. It also requires adding that same LAN
  IP as a `<domain>` entry to the debug-only
  `app/src/debug/res/xml/network_security_config.xml` — a per-developer edit; do not commit your
  real IP there, and never copy this exception into the release manifest.

Rebuild/reinstall after changing `local.properties` (Gradle only re-reads it on a new build).

## Key Conventions

- All code (variables, functions, comments, resource ids) MUST be in English. Tutorial lessons
  in `tutorial/` are written in **Spanish**.
- Persist data on-device; there is no backend yet. If/when a backend is added, sensitive logic
  and data ownership move there (and the API Contract section is reintroduced).
- Security is an MVP requirement, not a stretch goal.
- Kotlin/Compose conventions:
  - UI is Jetpack Compose (Material 3). No XML layouts.
  - Screen state is exposed via `ViewModel` + `StateFlow`; Composables stay stateless where
    possible (state hoisting).
  - Dependency versions live in `gradle/libs.versions.toml` (version catalog) — do not hardcode
    versions in `build.gradle.kts`.
  - Use the Gradle wrapper (`./gradlew`), never a system-wide `gradle`.

## Tutorial Methodology (binding for every feature)

This project is a progressive Kotlin/Android tutorial. For **every** feature added:

1. **Teach something new.** Each feature must introduce new, progressively harder concepts
   (start basic, build up). Reuse and reference concepts from earlier lessons.
2. **Ship a Spanish lesson.** Add `tutorial/NN-topic.md` (Spanish) that explains the new
   concepts and walks through the code that was written. Number it after the previous lesson.
   This lesson is part of the mandatory **Documentation Update** — a feature is not done without
   it (`/doc-check` covers it).
3. **Keep the code exemplary.** Code is didactic material: clear names, small functions, comments
   where a concept is first introduced (in English, per conventions).

The roadmap of upcoming features and their target concepts lives in the plan and in
`docs/prompts/`. Each future feature is started from a **fresh session** by pasting its prompt
from `docs/prompts/NN-*.md`.

## Execution Policy

- NEVER run anything in a scratch/temporary directory (e.g. `/tmp/...`), and NEVER execute
  commands, tests, installs, or tooling outside the project folder.
- All commands MUST run inside the project tree (the Gradle wrapper's own caches under
  `~/.gradle` and the Android SDK under `~/Android/Sdk` are the standard tooling locations and
  are allowed).
- If a constraint prevents running something in place (missing dependency, unsuitable image,
  missing SDK package), STOP. Report the blocker and propose fixes (install the SDK package via
  `sdkmanager`, adjust config, add the dependency) and wait for the user to decide.

## Mandatory Workflow

### New Feature Workflow

When the user requests a new feature or enhancement, ALWAYS follow this sequence:

1. **Specification first**: Delegate to the `project-manager-docs` agent to define the feature. The
   spec is saved in `docs/specs/YYYY-MM-DD-feature-name.md`. Must include: Overview, User Stories,
   Acceptance Criteria, Out of Scope, Dependencies.
2. **User approval**: Present the spec. Do NOT proceed until the user explicitly approves.
3. **Create feature branch**: `feature/<short-name>` from `master`.
4. **Implement**: Use the appropriate agent(s) (`mobile-engineer` for app code).
5. **Test**: Use `qa-engineer` to create/update tests.
6. **Tutorial lesson**: Add the Spanish `tutorial/NN-*.md` for the feature (see Tutorial
   Methodology) — required before committing.
7. **Commit on the feature branch**: Never directly on `master`.

### Bug Fix Workflow

When the user reports a bug:

1. **Diagnose**: Understand the bug.
2. **Create bugfix branch**: `bugfix/<short-name>` from `master`.
3. **Fix and test**.
4. **Commit on the bugfix branch**.

### Branch Rules

- NEVER commit directly to `master`.
- Branch naming: `feature/<name>` or `bugfix/<name>`, lowercase, hyphen-separated.
- If already on a feature/bugfix branch, continue on it.

> Note: the `check-branch.sh` hook enforces this by blocking source edits on the main branch.
> `MAIN_BRANCHES` in that hook already includes `master`.

### Commit Messages

- **Max two lines.** State what feature/fix was added and, briefly, why — no bullet lists, no
  file-by-file breakdown, no restating the diff.
- Skip narrating implementation detail (which classes, which files) — that's what `git show` is for.

### Documentation Update (mandatory before committing)

Every PR that changes observable behaviour MUST update the relevant documentation in the same
branch. Check each item that applies (run `/doc-check` to audit this against your diff):

- **New feature?** → Add its Spanish tutorial lesson in `tutorial/NN-*.md` (Tutorial Methodology).
- **Setup/commands/SDK/versions changed?** → Update this `CLAUDE.md` (Structure / Development).
- **New dependency?** → Add it to the version catalog `gradle/libs.versions.toml`.
- **New permission / manifest change?** → Reflect it here and in the relevant lesson.
- **New sub-project/module?** → Add it to the Structure section above.
