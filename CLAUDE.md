# Never Late Again — Workspace

<!-- Installed by setup-claude.sh — project type: mobile · agents: project-manager-docs, android-engineer, devops-security-engineer, backend-engineer (+ qa-engineer, mobile-engineer: superseded by android-engineer for app work, kept for /test and architecture questions) -->

## Overview

Native **Android** app (Kotlin + Jetpack Compose) that helps people with ADD/ADHD manage their
time and the tasks they need to get done.

**The product comes first.** The goal is a complete, shippable commercial-grade app: correct,
secure, accessible, localized and maintainable. What "done" means is defined in **Definition of
Done** below.

The app is **client + backend**: the Android app is an **offline-first client** of a small
**Kotlin/Ktor + Postgres backend** that owns user accounts and tasks. Data is cached and fully usable
on-device (Room), but the backend is the source of truth for synced data. The backend runs locally
via `docker compose` (cloud hosting is not set up yet). **An account is optional** (guest mode): the
app is fully usable with no login, and guest tasks are merged into the account on sign-in — so the
backend owns *synced* accounts/tasks, but a guest's tasks live only on-device until they choose to
sign in.

The project also carries an **optional tutorial track** (`tutorial/`, Spanish lessons). Features
01–20 were each documented as a lesson; from now on a lesson is **decided per feature, not assumed**
— see **Tutorial Track (optional)** below.

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
│     │  │  ├─ NeverLateApplication.kt # @HiltAndroidApp — Hilt's root component
│     │  │  ├─ di/                    # Hilt modules — see the module map below
│     │  │  └─ ui/theme/              # Compose theme: Color / Theme / Type
│     │  └─ res/                      # strings, themes, launcher icon
│     ├─ test/                        # Local JVM unit tests
│     └─ androidTest/                 # Instrumented / Compose UI tests
├─ backend/                     # Kotlin + Ktor + Postgres service — its own Gradle build
│  ├─ build.gradle.kts          # Backend module config + dependencies (separate from the app)
│  ├─ docker-compose.yml        # Backend + Postgres for local dev
│  ├─ README.md                 # Run + smoke instructions (does NOT re-list endpoints; see contract)
│  └─ src/                      # Ktor app: auth (JWT), tasks REST, persistence
├─ tutorial/                    # Spanish lessons — OPTIONAL track (see Tutorial Track below)
└─ docs/
   ├─ api/                      # API contract — source of truth for client + server
   ├─ arquitectura.md           # Architecture decision log, feature by feature (the "why")
   ├─ mockups/                  # Master visual mockup + slice tracking table
   ├─ specs/                    # Feature specs (project-manager-docs)
   ├─ prompts/                  # Ready-to-paste session starters — one per feature, shipped and pending
   ├─ conceptos-pendientes.md   # Tutorial-track backlog (learning concepts), NOT the product roadmap
   ├─ ideillas.md               # Product ideas from the user, each turned into a prompt
   ├─ diferidos.md              # Backlog of work this project's own specs deferred (Out of Scope / Risks)
   └─ articles-api/             # Article catalog content (now the backend seed source)
```

### Current module map (`app/src/main/java/com/neverlate/`)

What each package holds **today**. For *why* it looks like this — the decision behind each piece —
see [`docs/arquitectura.md`](docs/arquitectura.md).

| Package | Contents |
|---|---|
| `di/` | Hilt modules: `DatabaseModule`, `NetworkModule`, `StorageModule`, `RepositoryModule` + `Qualifiers.kt`, plus `WidgetEntryPoint.kt` (`@EntryPoint`, resolved via `EntryPointAccessors.fromApplication` from `PendingTasksWidget.provideGlance` — the escape hatch for a `GlanceAppWidget`, which Hilt never constructs and so can never carry `@Inject`; exposes the `@ReminderRepo` layer specifically, so a future write from the widget can't reenter `TaskSurfacesRefreshingRepository.refreshSurfaces()`). `RepositoryModule` assembles the four-layer `TaskRepository` decorator chain — order matters: `TaskSurfacesRefreshingRepository` (unqualified, what the app injects) → `ReminderSchedulingRepository` → `OutboxTaskRepository` → `RoomTaskRepository`. |
| `domain/tasks/` | Pure, JVM-testable rules shared across surfaces: `pendingRowsFor`, `ReminderPlanning.kt` (reminder scheduling), `urgencyLevelFor` (countdown urgency), `deadlineProgressFor` (progress-bar fraction), `TaskListShaping.kt` (`filteredBy`/`sortedBy`/`groupedByUrgency`/`shapedBy`), `TaskStats.kt` (`weeklyStatsFor` → `WeeklyTaskStats`), `RemainingTime.kt` (`remainingTimeFor` — text-free classifier for the compact countdown, rendered by `ui/components/RemainingTimeLabel.kt`), `ColorRole.kt` (the single shared urgency/priority → color-*role* mapping — `urgencyColorRole`/`priorityColorRole` — consumed by thin per-world resolvers in Compose and Glance; see the `ui/widget/` row). |
| `domain/sync/` | Pure reconciliation logic for the sync engine (last-write-wins by `updatedAt`, delete wins over edit). JVM-testable, no Android deps. |
| `data/tasks/` | Room entities/DAOs for `Task` (incl. `completedAt`, `priority` enum via `@TypeConverter`, sync metadata `serverId`/`updatedAt`/`syncState`/`deleted`), the `task_outbox`, and `TaskTiming.kt` (locale-aware formatting). |
| `data/articles/` | Retrofit `ArticlesApi` + `ArticleDto` (wire shape, mapped by `toDomain()`), Room `ArticleEntity`/`ArticleDao`, `article_remote_keys`, `ArticlesRemoteMediator` (Paging 3), `CachingArticleRepository` (Room = single source of truth). |
| `data/auth/`, `data/sync/` | `EncryptedTokenStorage` (Keystore-backed, access + refresh token), `AuthInterceptor` (Bearer), `TokenAuthenticator` (single-flight `401` refresh), `SyncEngine` (push outbox / pull `?since=`), `AuthRepositoryImpl`. |
| `ui/navigation/` | `AppNavHost` (auth gate: `Guest`/`LoggedIn` are **separate `when` arms** — do not merge them, guest adoption depends on the recomposition) and `MainAppNavHost` (bottom `NavigationBar` on compact, `NavigationRail` on medium/expanded, both over one shared `MainNavGraph`). |
| `ui/tasks/`, `ui/articles/`, `ui/settings/`, `ui/stats/` | The screens. Tasks/Articles/Settings are peer top-level destinations; Article Detail, Task Edit, Stats and Login/Register are secondary routes (no bottom bar). `ArticlesListDetailPane` gives Articles a two-pane layout at expanded width. |
| `ui/components/` | Shared UI building blocks: `MessageState` (empty/error/loading state used by Tasks, Articles, Stats), `ReadableWidthContainer` (max-640dp centered constraint on large windows), `RemainingTimeLabel.kt` (`formatRemainingLabel(Context, Long)` — the single home of compact remaining-time text, called by the task card, widget and notification). |
| `ui/notification/` | Lock-screen notification + foreground service, plus the reminder scheduler, `ReminderReceiver`, `BootReceiver`/`BootRescheduleWorker`. Two notification channels: silent `tasks_pending`, alerting `task_reminders`. Two alarm kinds share that alerting channel — `ReminderKind.LEAD_TIME` (feature 09, N minutes before a deadline) and `ReminderKind.TIME_UP` (times-up-alert feature, fires the instant a task's `timerEndsAt`/`deadline` actually runs out, `min` of the two when both apply) — namespaced by `requestCodeFor(taskId, kind)` and a distinct `Intent.action` so the two alarms can never collide (see `docs/arquitectura.md`'s D1). `BootRescheduleWorker` is also enqueued from `NeverLateApplication.onCreate` (cold start), not just after `BOOT_COMPLETED`. |
| `ui/widget/` | Home-screen widget (Glance). Gets its `TaskRepository` from Hilt via `di/WidgetEntryPoint.kt` (`PendingTasksWidget.provideGlance` no longer hand-builds `NeverLateDatabase`/`RoomTaskRepository`). Colors come from the app's own schemes via `glance-material3`'s `ColorProviders` bridge, **not** from `MaterialTheme` — a Glance composition has no access to it. The urgency/priority → color **mapping** lives once in `domain/tasks/ColorRole.kt`; `WidgetColors.kt`'s `urgencyColorProvider` / `Priority.glanceIndicatorColor()` are thin resolvers over that shared role token (their Compose counterparts, `colorForUrgency` / `Priority.indicatorColor()`, resolve the same roles against `MaterialTheme`/`NeverLateExtras`) plus hand-written `ColorProvider(day, night)` pairs for the roles the bridge doesn't carry: `calm`/`soon` and `outlineVariant`. Rounded corners come from a shape drawable + `ColorFilter.tint`, never `GlanceModifier.cornerRadius` (API 31+ vs `minSdk` 24). |
| `ui/theme/` | `Color`/`Theme`/`Type` — Material 3 tokens plus `NeverLateExtras` (urgency colors). `LightColorScheme`/`DarkColorScheme` are `internal` (not `private`) because the widget is a second consumer. Theme preference (light/dark/system) is persisted in the shared `user_prefs` DataStore. |

Persistence at a glance: **`NeverLateDatabase` is at version 6**, `exportSchema = true`, schemas
committed under `app/schemas/`. Migrations from v3 onward are **additive and tested**
(`MigrationTestHelper`) — never destructive, because guest-mode tasks live only on-device.
User preferences (theme, reminders on/off + lead time, onboarding) live in the `user_prefs`
DataStore; **tokens never do** — they live in the Keystore-backed encrypted store.

Security note that outlives its feature: the local dev backend is plaintext HTTP, allowed only by a
**debug-build-only** network security config (`app/src/debug/res/xml/network_security_config.xml`)
scoped to `10.0.2.2`/`localhost`. **Before any real deployment the backend must be served over
HTTPS**; never widen this exception or copy it into the release manifest.

## API Contract

The app now talks to a backend, so the HTTP contract between client (`app/`) and server (`backend/`)
is a **first-class, committed artifact** and the **single source of truth** for both sides: it is
authored/changed **first**, and client and server follow. Do not let the client and server drift from
it, and do not re-document endpoints elsewhere (the backend `README.md` points to the contract rather
than re-listing routes).

- **Contract:** [`docs/api/contract.md`](docs/api/contract.md) — endpoints (`/auth/register`,
  `/auth/login`, `/auth/refresh` + `/auth/logout`, `/tasks` CRUD + `GET ?since=` for pull,
  and the public paginated `GET /articles?page=&size=` (§7 — the only unauthenticated route)),
  the `TaskDto` wire shape (deliberately distinct from the Room `Task` entity), the JSON error envelope,
  auth (Bearer access token + refresh-token rotation/revocation/reuse, §2.1), and sync
  semantics.
- **Rule:** any change to request/response shapes, status codes, or auth updates `docs/api/contract.md`
  in the same change, and both sides are reconciled to it. Sensitive logic (ownership checks,
  validation, authority over `id`/`updatedAt`) lives on the **server**; the client is untrusted.

## Development

- **JDK: 21 — and only 21.** Gradle 8.x cannot run on a JDK 25 and aborts with an inscrutable
  `IllegalArgumentException: 25.0.3` before any build logic runs. Where the system JDK is newer than
  21, pin the supported one **once**, machine-wide, in the untracked `~/.gradle/gradle.properties`:

  ```properties
  org.gradle.java.home=/home/aritz/android-studio/jbr   # the Android Studio bundled JBR, a JDK 21
  ```

  With that in place a plain `./gradlew` just works. Do **not** pass `JAVA_HOME=` per command: it is
  easy to forget on one invocation and every change of value forks a new 2 GB daemon.
- **Android SDK:** `~/Android/Sdk` (configured in `local.properties` via `sdk.dir`).
- **SDK config:** `compileSdk = 36`, `targetSdk = 36`, `minSdk = 24`.
- Extra SDK packages/licenses: `~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager`.

```bash
# Build the debug APK
./gradlew :app:assembleDebug

# Install the debug build on a running device/emulator
./gradlew :app:installDebug

# Unit tests (JVM) and instrumented tests (needs a running emulator).
# Agents invoke these with a `timeout` prefix and no JAVA_HOME — see Build & test execution.
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest

# A single test class — the scoped form agents use while iterating
./gradlew :app:testDebugUnitTest --tests "com.neverlate.domain.tasks.TaskListShapingTest"

# Launch the installed app
adb shell am start -n com.neverlate/.MainActivity
```

Alternatively, open the project in **Android Studio** (`~/android-studio/bin/studio.sh`) and use
the Run button.

| Target | How to run |
|--------|-----------|
| Android emulator | `~/Android/Sdk/emulator/emulator -avd Nexus_5X_API_29_x86 &` then `adb wait-for-device` |

### Backend

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
- **Room is the local source of truth; the backend is the authority for synced data.** Every screen
  reads from Room and works offline; mutations go through the outbox and are reconciled by the sync
  engine. Sensitive logic (ownership checks, validation, authority over `id`/`updatedAt`) lives on
  the **server** — the client is untrusted.
- Security is a shipping requirement, not a stretch goal.
- Kotlin/Compose conventions:
  - UI is Jetpack Compose (Material 3). No XML layouts.
  - Screen state is exposed via `ViewModel` + `StateFlow`; Composables stay stateless where
    possible (state hoisting).
  - Dependency versions live in `gradle/libs.versions.toml` (version catalog) — do not hardcode
    versions in `build.gradle.kts`.
  - Use the Gradle wrapper (`./gradlew`), never a system-wide `gradle`.

## Definition of Done

The product bar. A feature is done when **all** of these hold — this list replaced the tutorial
lesson as the criterion for "finished".

- **Tests pass.** Pure logic (`domain/`) has JVM unit tests; UI/DB behaviour that can only be proven
  on a device has an instrumented test. `timeout 600 ./gradlew :app:testDebugUnitTest --console=plain`
  is green before committing — run once, by the orchestrator (see **Build & test execution**).
- **Migrations are additive and tested.** Any Room schema change bumps the version, ships a
  hand-written `Migration`, commits the exported `app/schemas/N.json`, and proves data survival with a
  `MigrationTestHelper` test. **Never** fall back to a destructive migration: guest-mode tasks exist
  only on-device and would be lost.
- **The contract is updated first.** Any change to request/response shapes, status codes or auth
  updates `docs/api/contract.md` in the same change, with client and server reconciled to it.
- **Security holds.** No secrets in tracked files (`local.properties` is git-ignored); the cleartext
  network exception stays **debug-only**; validation and ownership checks live on the server.
- **Accessibility and i18n hold.** Touch targets ≥ 48dp, meaningful `contentDescription`s, layout
  reflows at the largest font scale; all user-facing text in `strings.xml` with the Spanish base and
  the English `values-en` variant kept in sync (counts via `<plurals>`, dates/numbers per `Locale`).
- **Every state is designed.** Each screen covers loading, empty and error — reuse
  `ui/components/MessageState` instead of inventing per-screen states.
- **The visual ACs pass.** The spec's visual acceptance criteria are verified and
  `docs/mockups/README.md` reflects what shipped (see **Design in the Workflow**).
- **Docs match reality.** See **Documentation Update** below.

Known production gaps, each to be taken as its own spec'd feature — do not improvise them inside an
unrelated change: **CI** (there is no `.github/` yet: build + unit tests on every PR),
**signed release + versioning**, and **crash reporting / observability**.

## Tutorial Track (optional)

The repo doubles as a Spanish Kotlin/Android tutorial (`tutorial/`, features 01–20). **This is now an
optional track, decided per feature — not an obligation.** Plenty of work is product change that
teaches nothing new, and those features ship with no lesson.

**Ask, don't assume.** When starting a feature, ask the user whether it carries a lesson **before**
writing the spec, using an `AskUserQuestion` dialog (not prose) with these options:

- **Sí, con lección** — the feature introduces concepts worth teaching.
- **No** — product change only.
- **Decidir al final** — revisit after implementation, before committing.

Record the answer in the spec as a `Tutorial:` field. The spec's approval then covers it, and
`/doc-check` audits the lesson **only if the spec asked for one**.

**If the feature carries a lesson**, the existing rules apply unchanged:

- Write it in **Spanish** as `tutorial/NN-topic.md`, explaining the new concepts and walking through
  the code that was written.
- **Never renumber a shipped lesson** — the `feature NN` number is coupled 1:1 to hundreds of code
  comments, tests and git history. Interleave with letter suffixes instead (`03b`, `13d`).
- Flip its status to ✅ in **both** `docs/conceptos-pendientes.md` and `tutorial/README.md`.

**If it does not**, no lesson is written, no number is reserved, and the feature simply stays out of
the tutorial track. Everything else in **Documentation Update** still applies.

`docs/conceptos-pendientes.md` is the **backlog of the tutorial track** (Kotlin concepts still
unexplained, one ready-to-paste session starter each) — it is *not* the product roadmap, and its
pending slots (**10b, 21**) are opportunities, not commitments.

`docs/prompts/` is broader than that track: it holds a session starter per feature, **shipped and
pending alike** (shipped prompts are kept, and specs cite them as `Prompt origen`). Three documents
index it, by where the work came from: `conceptos-pendientes.md` (tutorial slots),
`docs/ideillas.md` (product ideas from the user) and `docs/diferidos.md` (work this project's own
specs deferred in their *Out of Scope* / *Risks* sections). None of the three is a commitment.

Independently of any lesson, **keep the code exemplary**: clear names, small functions, and a comment
in English where a non-obvious concept first appears.

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

1. **Ask about the tutorial**: Before anything else, ask the user via `AskUserQuestion` whether this
   feature carries a Spanish lesson (*sí / no / decidir al final*). See **Tutorial Track (optional)**.
2. **Specification first**: Delegate to the `project-manager-docs` agent to define the feature. The
   spec is saved in `docs/specs/YYYY-MM-DD-feature-name.md`. Must include: Overview, User Stories,
   Acceptance Criteria, **Visual & UX Design**, Out of Scope, Dependencies, Risks, and the
   **`Tutorial:`** field carrying the answer from step 1. See **Design in the Workflow** below for
   what the Visual & UX Design section must contain.
3. **User approval**: Present the spec. Do NOT proceed until the user explicitly approves — approval
   covers **behaviour, look and the tutorial decision** (all three are part of what is signed off).
4. **Create feature branch**: `feature/<short-name>` from `master`.
5. **Implement and test**: Delegate to the `android-engineer` agent — **one** agent writes the app
   code, writes its tests and runs them scoped. Do not split implementation and tests across two
   agents: the handoff costs a full cold context and puts two actors on the same working tree.
   Implement to the spec's **visual acceptance criteria**, not just its behavioural ones.
6. **Gate**: Once the agent has handed back, the orchestrator runs the full suite **once**
   (see **Build & test execution** below) and reviews the diff. That review is what replaces the
   second pair of eyes the old two-agent split provided — do not skip it. Meet the
   **Definition of Done**.
7. **Design review**: Verify the built UI against the feature's **visual acceptance criteria** and
   its slice of the master mockup, and update the mockup tracking (`docs/mockups/README.md`) to mark
   what this feature delivered vs. what stays pending. See **Design in the Workflow** below.
8. **Tutorial lesson — only if the spec says so**: if the spec's `Tutorial:` field asked for one (or
   was *decidir al final* and the user now says yes), add the Spanish `tutorial/NN-*.md` before
   committing. Otherwise skip this step entirely.
9. **Commit on the feature branch**: Never directly on `master`.

### Build & test execution (who runs what)

Binding for the orchestrator and for every subagent. These are not style preferences: each rule
here exists because its absence produced a stuck run.

- **One actor touches the tree at a time.** The orchestrator does not invoke Gradle or Git while a
  subagent is live, and vice versa. `.gradle/*.lock` and `app/build/test-results/` are shared
  resources — two concurrent invocations either block on the lock (which looks exactly like a hang)
  or overwrite each other's results and produce failures that are not real.
- **Never poll.** Background work notifies on completion by itself. No `jobs`, no repeated
  `BashOutput` checks, no turn whose only content is "still waiting".
- **Foreground, with an explicit `timeout N`.** That timeout is the only thing bounding a hang from
  the outside. There is no reason to background a test run: the full unit suite takes **~15 s** warm
  and the scoped runs are faster still, all far inside the tool's 10-minute foreground limit. If a
  command ever does exceed that ceiling, launch it in the background **once** and let its completion
  notification end the wait — never a poll, and never a second copy of the same command.
- **One JDK, one daemon.** Invoke Gradle with no `JAVA_HOME=` prefix. Alternating between the
  system JDK and the Android Studio JBR makes Gradle treat the running daemon as incompatible and
  fork a fresh 2 GB one, throwing away every warm cache.
- **The full suite runs exactly once**, by the orchestrator, as the gate before committing:

  ```bash
  timeout 600 ./gradlew :app:testDebugUnitTest --console=plain
  ```

  Subagents only ever run scoped, foreground runs filtered with `--tests`. Gradle itself will now
  kill any single test task that runs longer than ten minutes (`app/build.gradle.kts`), so a hang
  fails the build instead of stalling the session.
- **A timeout is a blocker, not a retry.** If a command hits its `timeout`, report it and stop —
  see **Execution Policy** above. Relaunching it blindly is how a session burns an hour.

> **`android-engineer` supersedes the old pair.** Specs and session starters written before this
> change (most of `docs/prompts/`) end with an `Agentes:` line naming `mobile-engineer` for the code
> and `qa-engineer` for the tests. Read that as **`android-engineer` for both** — those two agents
> are kept on disk (`qa-engineer` still backs `/test` for an independent pass, `mobile-engineer` for
> mobile-architecture questions), but app work is no longer split between them.

### Bug Fix Workflow

When the user reports a bug:

1. **Diagnose**: Understand the bug.
2. **Create bugfix branch**: `bugfix/<short-name>` from `master`.
3. **Fix and test**. Add a regression test that fails before the fix.
4. **Commit on the bugfix branch**.

Bug fixes carry **no tutorial lesson** by default and the question is not asked — write one only if
the user explicitly asks (as happened once with `tutorial/12b-keystore-recuperacion.md`).

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

- **Request/response shape, status code or auth changed?** → `docs/api/contract.md`, with client and
  server reconciled to it.
- **Visible UI change?** → Update the mockup tracking (`docs/mockups/README.md`): mark the slice this
  feature delivered and anything still pending (see **Design in the Workflow**).
- **New dependency?** → Add it to the version catalog `gradle/libs.versions.toml` — never a hardcoded
  version in a `build.gradle.kts`.
- **New permission / manifest change?** → Reflect it in this `CLAUDE.md`.
- **Room schema changed?** → Version bumped, additive migration + exported `app/schemas/N.json` +
  `MigrationTestHelper` test (see **Definition of Done**).
- **Setup/commands/SDK/versions changed?** → Update this `CLAUDE.md` (Structure / Development).
- **New sub-project/module or architectural decision worth remembering?** → Structure section above,
  and add the decision to `docs/arquitectura.md`.
- **Did the spec ask for a tutorial lesson?** → Add `tutorial/NN-*.md` and flip its status in
  `tutorial/README.md` + `docs/conceptos-pendientes.md`. **If the spec said no, skip this — it is not
  a missing item.**

## Design in the Workflow

Design is a **first-class part of every feature**, not an afterthought or an optional reference. The
app has a visual direction; the workflow must keep the shipped UI converging on it instead of drifting.
This section is binding wherever the New Feature Workflow references it.

### Source of truth: the master mockup (north star)

- [`docs/mockups/rediseno-ux-ui.html`](docs/mockups/rediseno-ux-ui.html) is the **single visual source
  of truth** — the "north star" for the app's look (brand color, hierarchy, task-card treatment,
  progress bars, bottom nav, etc.). It is *direction*, not production code: do not copy its HTML/CSS,
  translate its **intent** into Compose using the app's real theme (`ui/theme/` — `NeverLateExtras`,
  the type scale, Material 3 tokens).
- The mockup shows the **aspirational end-state across many features at once**. No single feature
  implements all of it; each feature delivers **its slice**. Because the whole thing is never built in
  one go, we track which slices are done vs pending — otherwise the gap silently compounds (which is
  exactly what happened before feature 18: the palette landed in feature 16, but branded app bars and
  task-card progress bars were deferred and then forgotten).
- [`docs/mockups/README.md`](docs/mockups/README.md) is that **tracking table**: mockup element →
  owning feature → status (✅ done / 🟡 partial / ⬜ pending). It is updated in the **Design review**
  step of every feature that touches UI, and is the canonical answer to "why does the app not yet look
  like the mockup?" — the pending rows *are* the backlog.

### Visual & UX Design section (in every spec)

The `project-manager-docs` spec MUST include a **Visual & UX Design** section that, for this feature's
screens:

- Names the **slice of the master mockup** this feature implements (link the relevant screen/element),
  and explicitly states what visual polish is **deferred** (and to where — a future feature or a
  pending row in the tracking table). Deferring is fine; deferring *silently* is not.
- Lists **visual acceptance criteria** alongside the behavioural ones — concrete, checkable statements
  (e.g. "task cards show a time-elapsed progress bar using `NeverLateExtras` urgency colors", "the top
  bar uses the brand container color", "touch targets ≥ 48dp", "layout reflows at the largest font
  scale"). These are part of *done*, verified in the Design review step.
- Reuses the app's **theme tokens and existing components** rather than inventing one-off styling —
  the same "extend, don't duplicate" rule that applies to logic applies to UI.

### Design review (gate, before commit)

After implementation + tests, verify the built UI against the spec's **visual acceptance criteria** and
the feature's mockup slice (`/run` to see it in the real app where practical), then update
`docs/mockups/README.md`. A feature that touches UI is **not done** until its visual ACs pass and the
tracking table reflects reality. Missing visual ACs are treated like a failing test, not a nice-to-have.
