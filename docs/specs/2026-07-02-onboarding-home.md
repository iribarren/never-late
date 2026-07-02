# Feature Spec — Onboarding + Home

- **Date:** 2026-07-02
- **Feature:** Onboarding + Home
- **Suggested branch:** `feature/onboarding-home`
- **Tutorial lesson:** `tutorial/01-onboarding-home.md` (Spanish, required before committing)
- **Status:** Draft — awaiting user approval

---

## Overview

This feature replaces the current static "Hola Mundo" screen (Lesson 00) with the app's first
real user flow: a one-time **onboarding** step and a persistent **home** screen.

On the very first launch the user is greeted by an **Onboarding** screen where they enter their
data (at minimum, their name). Saving marks the user as *onboarded* and takes them to **Home**.
On every subsequent launch the app skips onboarding and opens **Home** directly, showing a
personalized greeting and a list of app option placeholders for features that will arrive in
later lessons (tasks, articles, …).

Because "Never Late Again" is a progressive Kotlin/Android tutorial, this feature is also the
vehicle for introducing the first substantial Android app-architecture concepts. Everything is
**local-only**: the name and the onboarded flag are persisted on-device via DataStore
(Preferences). There is no backend.

### New concepts this feature teaches

This is the second lesson and the first to go beyond static UI. It must introduce, building on
Lesson 00 (`@Composable`, `Scaffold`, `Column`, `Text`, `Modifier`, theme):

- **Compose state:** `remember`, `mutableStateOf`, and **state hoisting** (stateless composables
  driven by state passed in + callbacks passed out).
- **Input & forms:** `TextField`, `Button`, and basic **form validation** (e.g. non-blank name).
- **`ViewModel` + `StateFlow`:** exposing screen state to the UI and surviving configuration
  changes (rotation), collected with `collectAsStateWithLifecycle`.
- **Navigation Compose:** a nav graph with two destinations (onboarding, home) and startup
  routing between them.
- **DataStore (Preferences):** persisting the user's name and the `onboarded` flag on-device,
  read as a `Flow`.
- **Material 3:** `Scaffold` with a `TopAppBar`.

Concepts are introduced progressively (state → input → ViewModel → persistence → navigation) and
each is explained in the mandatory Spanish tutorial lesson.

---

## Goals

- On first launch, the user can enter their name and be persistently remembered.
- On later launches, the app opens straight to a personalized Home without asking again.
- The name and onboarded flag survive app restarts and process death (persisted on-device).
- Screen state survives configuration changes (e.g. screen rotation) via `ViewModel`.
- The code remains exemplary/didactic and each new concept above is demonstrated cleanly and
  explained in `tutorial/01-onboarding-home.md`.
- Ships with a `ViewModel` unit test and a Compose UI test of the onboarding form.

### Non-functional requirements

- **Performance:** startup routing must not visibly flash the wrong screen; while the persisted
  state is loading, show a neutral loading state rather than defaulting to onboarding.
- **Security:** no sensitive data is collected (name only). Data stays on-device in the app's
  private DataStore; nothing is logged or transmitted.
- **Robustness:** first read from DataStore (empty store) yields sensible defaults
  (`name = ""`, `onboarded = false`) without crashing.

---

## User Stories

### US-1 — First-time onboarding

> As a **new user**, I want to enter my name the first time I open the app, so that the app can
> greet me personally and remember me.

**Acceptance Criteria**

- On a fresh install (no persisted data), the app opens on the **Onboarding** screen.
- The Onboarding screen shows a title, a `TextField` labelled for the name, and a save `Button`.
- The save button is **disabled** while the name field is blank (only whitespace counts as blank).
- Entering a non-blank name enables the button; tapping it persists the name and sets
  `onboarded = true`, then navigates to **Home**.
- After saving, the user cannot navigate back to Onboarding via the system back button (the
  onboarding destination is removed from the back stack).

### US-2 — Returning user goes straight to Home

> As a **returning user**, I want the app to open directly on Home, so that I don't have to
> re-enter my name every time.

**Acceptance Criteria**

- When `onboarded == true` in persisted storage, the app opens on **Home** and never shows
  Onboarding.
- Home displays a personalized greeting that includes the persisted name
  (e.g. "Hola, {name}").
- The persisted name and flag survive a full app kill/restart (process death), not just
  in-memory navigation.

### US-3 — Home shows upcoming app options

> As a **user on Home**, I want to see the areas the app will offer, so that I understand what
> the app does and what's coming.

**Acceptance Criteria**

- Home renders a `Scaffold` with a `TopAppBar` showing the app name.
- Home shows a list of option entries as placeholders (at least **Tasks** and **Articles**).
- Placeholder options are visibly non-functional for now (e.g. tapping shows nothing or a simple
  "coming soon" indication) — no navigation to unbuilt features.

### US-4 — State survives rotation

> As a **user filling in the form**, I want my typed name to survive a screen rotation, so that I
> don't lose my input.

**Acceptance Criteria**

- Typing a name and rotating the device preserves the entered text (state is held by the
  `ViewModel`, not lost with the composable).

### US-5 — Validation feedback

> As a **user**, I want the form to prevent me from saving an empty name, so that I don't create
> a meaningless profile.

**Acceptance Criteria**

- Attempting to save with a blank name is not possible (button disabled); no navigation occurs.
- Leading/trailing whitespace is trimmed before persisting; a name of only spaces is treated as
  blank.

---

## Technical Approach

Single Gradle module (`:app`), Kotlin + Jetpack Compose (Material 3), MVVM with unidirectional
data flow. Suggested package layout under `app/src/main/java/com/neverlate/`:

- `data/`
  - `UserPreferencesRepository` — wraps a Preferences **DataStore** instance; exposes the
    persisted state as a `Flow` (name + `onboarded`) and a `suspend fun` to save onboarding data.
    DataStore keys: `user_name` (String), `onboarded` (Boolean).
- `ui/onboarding/`
  - `OnboardingViewModel` — holds a `StateFlow<OnboardingUiState>` (name text + save-enabled +
    validation), handles name changes and the save action (writes to the repository).
  - `OnboardingScreen` — stateless composable; receives state + `onNameChange` / `onSave`
    callbacks (state hoisting). Renders `TextField` + `Button`.
- `ui/home/`
  - `HomeViewModel` — exposes the persisted name for the greeting.
  - `HomeScreen` — `Scaffold` + `TopAppBar` + a list of option placeholders.
- `ui/navigation/`
  - `AppNavHost` — Navigation Compose nav graph with `onboarding` and `home` destinations.
  - Startup routing: a small app-level state reads the persisted `onboarded` flag; while it is
    still loading show a neutral loading state; then set the **start destination** accordingly
    (or navigate + pop onboarding off the back stack after save).
- `MainActivity` — hosts `NeverLateTheme { AppNavHost(...) }` in `setContent`.

**Data flow:** DataStore `Flow` → repository → `ViewModel` (`StateFlow`) → composable via
`collectAsStateWithLifecycle`. UI events flow back through callbacks to the `ViewModel`, which
persists via a `suspend` write on `viewModelScope`.

**Testing approach:**

- **Unit test** (`app/src/test/`) for `OnboardingViewModel`: blank name keeps save disabled;
  non-blank enables it; saving invokes the repository with the trimmed name and `onboarded = true`.
  Use a fake/in-memory repository (no real DataStore in the JVM unit test).
- **Compose UI test** (`app/src/androidTest/`) for the onboarding form: button disabled when
  empty, enabled after typing, and typing + save transitions away from onboarding.

**Version catalog:** all new dependencies added to `gradle/libs.versions.toml` (versions +
libraries + any bundles), referenced as `libs.<alias>` in `app/build.gradle.kts` — never
hardcoded. No new Android permission or manifest change is expected (local-only, no network).

---

## Out of Scope

- Any backend, sync, account, or authentication — the app remains local-only.
- Editing or clearing the profile after onboarding (no "edit name" / "reset" flow yet).
- Collecting any data beyond the name (age, avatar, preferences, ADHD-specific settings).
- Implementing the actual **Tasks** or **Articles** features — Home only shows placeholders.
- Real navigation from the Home option placeholders to functional screens.
- Multiple user profiles.
- Theming/dark-mode work, localization of the app UI, and accessibility polish beyond defaults.
- Data encryption at rest (name is non-sensitive; standard app-private DataStore is sufficient).
- Onboarding tutorial/tips carousel or multi-step onboarding — a single-field screen only.

---

## Dependencies

**Technical (new libraries, via the version catalog):**

- **DataStore Preferences** — `androidx.datastore:datastore-preferences` (persist name +
  onboarded flag).
- **Navigation Compose** — `androidx.navigation:navigation-compose` (nav graph + routing).
- **Lifecycle ViewModel Compose** — `androidx.lifecycle:lifecycle-viewmodel-compose` and
  `androidx.lifecycle:lifecycle-runtime-compose` (obtain `ViewModel` in Compose;
  `collectAsStateWithLifecycle`).
- Existing Compose BOM, Material 3, and test deps already present in the catalog are reused.

**Process / project:**

- New feature branch `feature/onboarding-home` off `master` (never commit to `master`).
- Mandatory Spanish tutorial lesson `tutorial/01-onboarding-home.md` before committing
  (Tutorial Methodology / `/doc-check`).
- `gradle/libs.versions.toml` updated for the new dependencies (Documentation Update rule).
- Build/test run in-place via the Gradle wrapper; the Compose UI test needs a running emulator
  (`./gradlew :app:connectedDebugAndroidTest`).

**Preconditions (already true):**

- Lesson 00 scaffold in place (`MainActivity`, `NeverLateTheme`, single-module Gradle project,
  `compileSdk/targetSdk = 36`, `minSdk = 24`).

---

## Risks

- **Startup flash / wrong first screen:** reading the persisted flag is asynchronous. If routing
  defaults to onboarding before the flag loads, returning users may briefly see the wrong screen.
  *Mitigation:* show a neutral loading state until the flag resolves, then pick the start
  destination.
- **Back-stack correctness:** after saving onboarding, the onboarding destination must be popped
  so back doesn't return to it. Easy to get wrong with Navigation Compose. *Mitigation:* covered
  by US-1 acceptance criteria and a UI test.
- **Version compatibility:** Navigation Compose / Lifecycle versions must be compatible with the
  current Compose BOM (`2024.12.01`) and Kotlin `2.1.0`. *Mitigation:* pin known-compatible
  versions in the catalog and verify the build before committing.
- **DataStore in JVM unit tests:** DataStore is Android-runtime dependent; using it directly in a
  local unit test is awkward. *Mitigation:* test the `ViewModel` against a fake repository;
  exercise real DataStore only in instrumented tests if needed.
- **Tutorial scope creep:** five new concepts in one lesson is a lot for a progressive tutorial.
  *Mitigation:* keep each concept's example minimal and clearly sequenced in
  `tutorial/01-onboarding-home.md`; reference Lesson 00 rather than re-explaining basics.
- **Emulator availability for UI test:** `connectedDebugAndroidTest` requires a running emulator;
  if unavailable, the Compose UI test can't run in place (per Execution Policy, stop and report
  rather than working around it).

---

## Review

Please review this spec and confirm the scope, user stories, and acceptance criteria. Per the
Mandatory Workflow, implementation must not begin until you explicitly approve. Once approved,
next steps are: create `feature/onboarding-home`, implement (`mobile-engineer`), add tests
(`qa-engineer`), write `tutorial/01-onboarding-home.md`, and commit on the branch.
