# Never Late Again — Workspace

<!-- Installed by setup-claude.sh — project type: mobile · agents: project-manager-docs, qa-engineer, devops-security-engineer, mobile-engineer -->

## Overview

Native **Android** app (Kotlin + Jetpack Compose) that helps people with ADD/ADHD manage their
time and the tasks they need to get done.

This is also a **teaching project**: the app is built as a progressive tutorial to learn Kotlin
and Android development. Each new feature must introduce new concepts, from the basics up. See
**Tutorial Methodology** below — it is binding for every feature.

The app is **local-only** for now (no backend). Data is persisted on-device.

## Structure

Standard single-module Gradle project:

```
never-late/
├─ settings.gradle.kts          # Modules + repositories
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
├─ tutorial/                    # Spanish lessons, one per feature (see Tutorial Methodology)
└─ docs/
   ├─ prompts/                  # Ready-to-paste prompts to start each feature in a new session
   └─ specs/                    # Feature specs (project-manager-docs)
```

As the app grows, feature code lives under `app/src/main/java/com/neverlate/` in packages such as
`ui/screens`, `ui/<feature>`, `data` (DataStore/Room), and `domain`. Current feature packages of
note: `ui/widget` (feature 05, home-screen widget), `ui/notification` (feature 06, lock-screen
notification + foreground service), `ui/settings` (feature 07, Settings screen + light/dark/system
theme preference persisted via the shared `user_prefs` DataStore and applied in `NeverLateTheme`),
and `domain/tasks` (rules shared by both surfaces, e.g. `pendingRowsFor`).

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
(`TasksNotificationService`, `foregroundServiceType="specialUse"`).

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
