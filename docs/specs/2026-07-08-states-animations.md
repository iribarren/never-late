# Feature 17 (extra) — Micro-interactions: empty states, animations & visual urgency

- **Status:** Approved — 2026-07-08. Open questions resolved: (1) snackbar on the **Tasks** screen (post-save nav unchanged); (2) **color-only** urgency for v1 — no progress bar; (3) thresholds **5 min / 60 min**; (4) animated strikethrough **deferred** (out of scope for v1).
- **Date:** 2026-07-08
- **Type:** UI polish + tutorial feature (no backend, no API contract, no DB version bump, no new permission, no new dependency)
- **Planned tutorial lesson:** `tutorial/17-estados-animaciones.md` (Spanish, numbered after the current latest lesson `tutorial/16-identidad-visual.md`)
- **Maps to pending concepts:** `docs/conceptos-pendientes.md` §4 "Arquitectura y Compose avanzado" — specifically the two proposed features under that section: *side-effects* ("snackbar/scroll automático al crear una tarea con `LaunchedEffect`") and *animaciones* ("animar el tachado y la desaparición de una tarea al completarla"). The urgency cue also draws on §4's `derivedStateOf` bullet.
- **Visual reference:** `docs/mockups/rediseno-ux-ui.html` (direction only — colors/proportions to interpret, not code to copy).

---

## Overview

This feature adds a layer of **micro-interactions** on top of screens that already work correctly:
better empty/error states, list animations, one-shot feedback when a task is created, and a
time-based **visual urgency** cue on each task's countdown. Nothing about the app's data, state
shape, or navigation changes — every item plugs into the **existing** `when(uiState)` branches,
`LazyColumn`s, `SnackbarHost`, and `CountdownTicker`.

Its second purpose is didactic: it is the tutorial's first dedicated pass over **Compose animations**
and a deeper look at **side-effects** (`LaunchedEffect`, `derivedStateOf`), both of which have so far
appeared only incidentally (`LaunchedEffect` was first used in lesson 13 without a lesson of its own;
animations have never been taught). See "New concepts taught" below.

The work is deliberately small in surface area and low in risk: it is polish, not new capability, so
it is a safe place to introduce animation and side-effect concepts without also fighting new data or
navigation problems.

---

## Goals

Success looks like:

- A user opening Tasks or Articles with nothing to show sees a **guiding** empty state (icon +
  message + optional action), not a bare centered line of text — and error states offer a retry.
- Tasks visibly **animate** into and out of the list when created, completed, or deleted, so state
  changes read as motion rather than an instant jump.
- Creating a task produces a brief, non-blocking **"task created"** confirmation (a `Snackbar`).
- A task's countdown communicates **urgency at a glance** — calm when there is plenty of time,
  escalating through a "soon" tier to an "urgent/overdue" tier — via color and/or a progress bar,
  without the user reading the exact number.
- All four are implemented by **extending existing structures**, with no change to `TasksUiState`,
  `ArticlesUiState`, `TaskUiModel`, the ViewModels' state shape, or the navigation graph.
- The tutorial gains a clear Spanish lesson (`17-estados-animaciones.md`) teaching animations and
  side-effects, consistent in tone with lessons 04 and 13.

Non-goals for "success": no new screens, no new user data, no measurable performance target beyond
"the once-a-second tick does not become more expensive" (see Risks).

---

## User Stories

### US-1 — Guiding empty state (Tasks & Articles)
**As a** user who has no tasks yet (or no articles loaded),
**I want** the empty screen to show an icon, a short explanation, and — where it makes sense — an
action button,
**so that** I understand the screen is empty *on purpose* and know what to do next instead of
wondering whether it failed to load.

**Acceptance criteria**
- A single **reusable** composable renders an empty/error state from parameters: an `ImageVector`
  icon, a message string, and an **optional** action (label + `onClick`); when no action is
  provided, no button is shown.
- `TasksScreen`'s `TasksUiState.Empty` branch renders this composable with a tasks-appropriate icon
  and message, and an action that starts task creation (same destination as the FAB).
- `ArticlesScreen`'s `ArticlesUiState.Empty` branch renders it with an articles-appropriate icon and
  message (no action, or a refresh action — see Open Questions).
- `ArticlesScreen`'s `ArticlesUiState.Error` branch renders it with an error message **and** a
  **Retry** action wired to the existing `onRefresh` (behaviour-preserving replacement of today's
  `ErrorArticles`).
- The `when(uiState)` structure in both screens is **unchanged** (same branches, same states); only
  the leaf composable each branch renders is swapped.
- The `Loading` branch still renders nothing (preserves today's "no one-frame flash of empty" note).
- All strings come from string resources (Spanish base + English variant), per feature 08.

### US-2 — Animated list changes
**As a** user creating, completing, or deleting a task,
**I want** the row to animate in or out,
**so that** the change is legible as motion rather than a jarring instant reflow.

**Acceptance criteria**
- The Tasks `LazyColumn` items use `Modifier.animateItem()` so insertions, removals, and reorders
  animate (the existing stable `key = { it.task.id }` is what makes this work).
- The Articles `LazyColumn` items likewise use `animateItem()` (stable `key = { it.id }` already
  present).
- Deleting a task animates the row out before it disappears; adding a task animates the new row in.
- Optionally, a completed/timed-out task's title uses an animated strikethrough or color change
  (per `docs/conceptos-pendientes.md` §4's "animar el tachado"); if included it must use
  `animate*AsState`/`AnimatedVisibility`, not a manual frame loop. *(Secondary — may be deferred to
  keep the lesson focused; see Out of Scope.)*
- No change to `TaskUiModel`, `TasksUiState`, or the list's data flow.

### US-3 — "Task created" confirmation
**As a** user who just saved a new task,
**I want** a brief confirmation message,
**so that** I have positive feedback that the task was created.

**Acceptance criteria**
- After a task is successfully created, a `Snackbar` reading "task created" (localized) is shown
  **once** per creation, via a `LaunchedEffect` keyed on a one-shot event/signal (not on
  recomposition — it must not re-fire on rotation or unrelated recompositions).
- It **reuses** the `SnackbarHost` + `SnackbarHostState` already wired (but currently unused) in
  `HomeScreen` — no second `SnackbarHost` is introduced. *(See Open Questions: the host lives on
  Home while creation currently returns the user to the Tasks screen; the implementer must confirm
  which screen shows the snackbar and wire the host accordingly — the intent is "reuse, don't
  duplicate".)*
- The snackbar does not block interaction and auto-dismisses (standard `Short` duration).
- Editing an existing task does **not** fire the "created" snackbar (creation only).

### US-4 — Countdown urgency cue
**As a** user scanning my task list,
**I want** each countdown to change color (and/or show a progress bar) as its deadline approaches,
**so that** I can spot what is urgent or overdue without reading every number.

**Acceptance criteria**
- Each task's countdown expresses an **urgency level** through color, matching the mockup's three
  tiers: **Calm** (green — "holgado, queda tiempo"), **Soon** (amber — "pronto, atención"),
  **Urgent/Overdue** (red — "urgente / vencido").
- The urgency level is **derived** from the same `remainingMillis`/`isTimedOut` the countdown text
  already uses, via `derivedStateOf`, so it is recomputed only when the tick actually crosses a
  threshold — **not** computed independently or on every recomposition.
- Proposed thresholds (tunable design defaults, expressed as pure logic so they are unit-testable):
  - **Overdue** → `isTimedOut == true` (remaining has reached 0). Red, and keeps today's "time's up"
    label.
  - **Urgent** → remaining ≤ 5 minutes (and not yet overdue). Red.
  - **Soon** → remaining ≤ 60 minutes (and > 5 minutes). Amber.
  - **Calm** → remaining > 60 minutes. Green.
- Optionally, a thin `LinearProgressIndicator` under the row fills toward the deadline, tinted by the
  same urgency level (per the mockup's progress bar). *(Secondary — see Open Questions on the
  progress denominator.)*
- The urgency color is applied on top of the existing brand theme (feature 16) and must remain
  legible in both light and dark themes; it must not be the **only** signal for the overdue state
  (the "time's up" text remains), for accessibility.
- No change to `TaskUiModel` or `TasksUiState`; urgency is a UI-layer derivation.

### US-5 — Tutorial lesson (mandatory per Tutorial Methodology)
**As a** learner following the tutorial,
**I want** a Spanish lesson explaining the animation and side-effect concepts this feature introduces,
**so that** the codebase stays a coherent progressive tutorial.

**Acceptance criteria**
- `tutorial/17-estados-animaciones.md` exists, in Spanish, numbered after lesson 16.
- It teaches the concepts in "New concepts taught" below, walking through the code actually written,
  and references prior lessons (04 for the list/countdown, 13 for `LaunchedEffect`) per methodology.

---

## Technical Approach

High-level strategy — extend, do not restructure. All work is in the UI layer (`app/`).

### 1. Reusable empty/error composable
- New composable in a new `ui/components/` package, e.g.
  `app/src/main/java/com/neverlate/ui/components/MessageState.kt` (proposed name — `EmptyState` /
  `StatusMessage` are alternatives). Signature roughly:
  `@Composable fun MessageState(icon: ImageVector, message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier)`.
- It renders the centered icon + message + optional `Button`, absorbing today's `EmptyTasks`,
  `EmptyArticles`, and `ErrorArticles` boxes.
- Plug into the **existing** branches: `TasksScreen` `TasksUiState.Empty`; `ArticlesScreen`
  `ArticlesUiState.Empty` and `ArticlesUiState.Error`. Delete the now-redundant private
  `EmptyTasks`/`EmptyArticles`/`ErrorArticles`.
- New string resources for the tasks empty action (and any new copy) in both `values/` and
  `values-en/`.

### 2. List animations
- Add `Modifier.animateItem()` to the row composable inside `items(...)` in both `TaskList`
  (`TasksScreen.kt`) and `ArticleList` (`ArticlesScreen.kt`). The stable `key`s already present are
  the prerequisite and are unchanged.
- Optional animated strikethrough/fade for completed tasks via `animateColorAsState` /
  `AnimatedVisibility` on the title (US-2, secondary).

### 3. "Task created" snackbar
- Reuse Home's existing `snackbarHostState` (`HomeScreen.kt`, lines ~87/119) — do not add a second
  host. Show the message from a `LaunchedEffect` keyed on a **one-shot** signal.
- Source of the signal: the save flow is `TaskEditViewModel.save()` → `TaskEditScreen` observes
  `isSaved` → `onSaved()` → `navController.popBackStack()` (see `AppNavHost.kt` ~199/283). The
  implementer wires a one-shot "task created" event into the screen that owns the reused host, so
  the `LaunchedEffect` fires exactly once (guard against re-firing on recomposition/rotation — e.g.
  a consumable event or a `SharedFlow`/one-shot flag, not a raw state boolean read every
  composition).
- **Open decision (flag for approval):** the reusable host currently lives on **Home**, but saving a
  task returns the user to the **Tasks** screen. Either (a) surface the snackbar on the Tasks screen
  by moving/adding the host wiring there while still "reusing" the pattern, or (b) show it on Home if
  the post-save landing is changed. This is the one ambiguity in the prompt and should be resolved
  before implementation — see Risks/Open Questions.

### 4. Countdown urgency
- Add a small **pure** helper (JVM-unit-testable, in the spirit of `ReminderPlanning.kt` /
  `TaskTiming.kt`), e.g. `urgencyLevelFor(remainingMillis: Long, isTimedOut: Boolean): UrgencyLevel`
  returning a `UrgencyLevel { Calm, Soon, Urgent, Overdue }` enum. Proposed location:
  `domain/tasks/` (pure, no Android deps) or alongside `formatRemaining` in
  `data/tasks/TaskTiming.kt`.
- In `TaskRow`, derive the level via `derivedStateOf { urgencyLevelFor(uiModel.remainingMillis, uiModel.isTimedOut) }`
  so it recomputes only when the tick crosses a threshold, and map the level → a theme color for the
  countdown `Text` (and, optionally, the tint + fraction of a `LinearProgressIndicator`).
- Urgency **colors** should be added as theme-level semantic colors (extending feature 16's palette)
  so light/dark both stay legible, rather than hardcoded hex in the composable.

### Files in scope
- `app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt` — empty state, `animateItem`, urgency.
- `app/src/main/java/com/neverlate/ui/articles/ArticlesScreen.kt` — empty/error state, `animateItem`.
- `app/src/main/java/com/neverlate/ui/home/HomeScreen.kt` — reuse existing `SnackbarHost` for the
  "task created" message (host wiring may extend to the Tasks screen per the open decision).
- **New:** `app/src/main/java/com/neverlate/ui/components/` — the reusable empty/error composable.
- Likely touched: a pure urgency helper (`domain/tasks/` or `data/tasks/TaskTiming.kt`), theme color
  additions (`ui/theme/`), and string resources (`res/values/strings.xml` + `res/values-en/strings.xml`).
- **New:** `tutorial/17-estados-animaciones.md` (Spanish lesson).

---

## New concepts taught (tutorial mandate)

To be developed in `tutorial/17-estados-animaciones.md` (Spanish); listed here so the spec captures
the didactic intent, per the Tutorial Methodology in `CLAUDE.md`:

- **Compose animations.** `animate*AsState` (animating a color/size value), `AnimatedVisibility`
  (animating appearance/disappearance), and `Modifier.animateItem()` in lazy lists. What animates
  "for free" (a stable-keyed list item's position/insertion/removal) vs. what you must orchestrate
  yourself (a strikethrough, a color transition).
- **Side-effects, in depth.** `LaunchedEffect` for **one-shot events** (showing a snackbar exactly
  once) and why keying/consuming the event matters so it does not re-fire on recomposition or
  rotation; `derivedStateOf` for **computed state** (the urgency level) so a value derived from
  fast-changing state does not over-recompose the whole screen. Builds directly on lesson 13's first
  `LaunchedEffect`.
- **Empty/error states as design.** Why a good empty state guides the user (icon + message + action)
  instead of leaving them staring at blank space, and how to extract a **reusable** parameterized
  composable (`icon`, `message`, optional `onAction`) used across two screens.

---

## Out of Scope

- Any backend, API contract, DB schema/version, permission, module, or dependency change (all
  animation/side-effect APIs used are already in the Compose BOM already on the classpath).
- Changing state shapes: `TasksUiState`, `ArticlesUiState`, `TaskUiModel`, or the ViewModels'
  public `StateFlow`s.
- New screens, new navigation destinations, or changing what data the app stores.
- Auto-scrolling the list to a newly created task (the `conceptos-pendientes.md` §4 idea pairs
  scroll with the snackbar; this feature ships the **snackbar** only — auto-scroll can be a later
  addition).
- A per-task sync badge or any expansion of feature 11's minimal sync UI (`SyncStatusHint` stays as
  is).
- Gesture/swipe-to-delete, drag-to-reorder, or any new interaction beyond the existing tap/FAB/dialog
  flows.
- Rich/animated snackbars, custom snackbar visuals, or snackbars for actions other than task
  creation.
- Motion/animation user preference toggle (respecting system "reduce motion" is noted as a Risk, not
  committed here).

---

## Dependencies

- **Existing structures that must remain as they are** (this feature relies on them):
  - Stable `key`s on both `LazyColumn`s (`key = { it.task.id }`, `key = { it.id }`) — prerequisite for
    `animateItem`.
  - The unused `SnackbarHost`/`SnackbarHostState` already wired in `HomeScreen`.
  - The `CountdownTicker`/`computeRemainingMillis`/`formatRemaining` pipeline feeding
    `TaskUiModel.remainingMillis` (feature 04) — the urgency level derives from it.
  - The `when(uiState)` branch structure in both screens.
- **Feature 16 (visual identity / brand palette)** — urgency colors should extend that palette rather
  than introduce ad-hoc hex, so this feature builds on it.
- **Feature 08 (i18n)** — all new copy needs Spanish + English string resources.
- **Compose animation APIs** (`androidx.compose.animation.*`, `animateItem`) — already available via
  the existing Compose BOM; **confirm no version-catalog change is needed** (expected: none).
- **Resolution of the Open Questions below** (especially the snackbar host location) before
  implementation begins.

---

## Risks / Open Questions

1. **Snackbar host location (main open question).** The reusable `SnackbarHost` lives on **Home**, but
   saving a task pops back to the **Tasks** screen (`AppNavHost.kt`). "Reuse, don't duplicate" and
   "show after creation" pull in different directions. **Decision needed:** show the snackbar on Tasks
   (extend the host pattern there) vs. on Home (change post-save landing). Recommendation: keep the
   post-save navigation as-is and surface the "task created" snackbar on the Tasks screen, reusing the
   same host pattern rather than literally the Home instance.
2. **Progress-bar denominator.** A "% toward deadline" bar needs a well-defined *total* window. A
   running timer has `elapsed/total`, but a not-yet-started, deadline-only task has no natural start
   for a fraction. **Decision needed:** either scope the progress bar to cases with a meaningful total
   (running timer, or duration-only tasks) and omit it otherwise, or treat the progress bar as fully
   optional and ship urgency **color** as the primary cue. Recommendation: ship color first; treat the
   bar as optional polish.
3. **Urgency thresholds are product judgement.** 5 min / 60 min are proposed defaults. They should be
   confirmed with the user and kept in the pure helper so they are trivial to tune and to unit-test.
4. **Accessibility.** Color must not be the sole urgency signal (the "time's up" text covers overdue);
   consider whether "urgent" also needs a non-color cue. Also consider honoring the system
   reduce-motion setting for the list animations (noted, not committed).
5. **`derivedStateOf` correctness.** The urgency derivation must read the ticking `remainingMillis` so
   it stays live, while still only recomputing across threshold crossings — a subtle point the lesson
   should make explicit and QA should cover (color changes at the right boundary, once).
6. **One-shot snackbar re-firing.** The classic `LaunchedEffect` pitfall — firing again on rotation or
   recomposition. Must use a consumable event, not a plain state read. QA should verify it fires once
   per creation and not on configuration change.

---

## Review

Please review this specification and confirm:
- The **snackbar host location** decision (Risk 1) and whether to keep post-save navigation as-is.
- Whether the **progress bar** is in scope for v1 or deferred to color-only (Risk 2).
- The **urgency thresholds** (5 min / 60 min) and tier names.
- Whether the optional **animated strikethrough** for completed tasks (US-2 secondary) is in scope.

Once approved, implementation proceeds on a `feature/<short-name>` branch per the New Feature
Workflow in `CLAUDE.md` (spec → approval → branch → `mobile-engineer` implementation → `qa-engineer`
tests → Spanish lesson `tutorial/17-estados-animaciones.md` → commit).
