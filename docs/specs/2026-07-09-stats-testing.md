# Feature 04c — Statistics screen + real task completion (introduction to testing)

- **Status:** Approved (2026-07-09) — real additive `Migration(3,4)` confirmed; ready to implement
- **Branch (suggested):** `feature/stats-testing`
- **Tutorial lesson:** `tutorial/04c-testing-estadisticas.md` (Spanish, numbered **04c**, between 04 and 05)
- **Roadmap:** `docs/conceptos-pendientes.md` §3 (Testing)
- **Type:** Teaching feature — **the pedagogical spine is testing**; completion + the statistics screen are the code the lesson tests. Scope is now **full-stack** (client + backend + API contract).

---

## Overview

The project already ships JVM tests (`app/src/test`) and Compose/instrumented tests
(`app/src/androidTest`), and several features write tests as a side effect — but **no lesson teaches
testing as its own topic**. Feature 04c makes testing the explicit subject, walking a learner through
the full testing pyramid on real code written in the same lesson:

- **pure functions** (`weeklyStatsFor`, and the existing `reconcilePulledTask`) → trivial, deterministic **JVM unit tests** (JUnit, arrange/act/assert, fakes),
- a **`ViewModel` + `StateFlow`** → **coroutine/`Flow` tests** (`runTest`, `StandardTestDispatcher`, virtual time),
- **stateless Composables** (the stats screen, the task-row mark-done toggle) → **Compose UI tests** (`createComposeRule`, semantics, `onNodeWithText` / `onNodeWithContentDescription`).

To make the statistics **honest** (not a deadline proxy), this feature also introduces **real task
completion**: a task gains a `completedAt` timestamp, users mark tasks done from the Tasks list, and
completion **syncs to the backend** through the existing `/tasks` endpoints. That turns 04c into a
genuinely full-stack slice — API contract, backend, Room schema, sync reconcile, and UI — which broadens
the *testing surface* (the whole point) while keeping testing as the through-line.

This document supersedes the initial draft's "deadline proxy / no backend / no DB change" approach: the
reviewer chose **real completion, synced end to end**.

---

## Goals

1. **Teach testing as a first-class topic**, end to end, on code written in the same lesson, with the
   "designing for testability" framing (pure functions + injectable clock + a repository seam).
2. Deliver **honest statistics** from a real `completedAt`, not a deadline proxy:
   completed-this-week, on-time %, and due-soon.
3. Add a **mark-done affordance** to the Tasks list that persists through the existing single write path
   (task row **and** outbox change row in the same transaction — feature 11's sync design).
4. **Sync completion** to the backend over the existing `/tasks` CRUD + `?since=` pull, reconciled by the
   existing last-write-wins-by-`updatedAt` rule — **no new endpoints, no new auth**.
5. Reuse existing seams and tokens (`TaskRepository`, `TaskTiming`, `ui/theme`, `MessageState`,
   `BrandIconChip`, `brandedTopAppBarColors()`) — "extend, don't duplicate."

Success looks like: `completedAt` flows correctly through contract → backend → Room → sync; marking a
task done updates the list and the stats; and every layer has the test the lesson describes.

---

## Resolved decisions (from review — recorded as decided, not open)

1. **Real completion field.** Add `completedAt: Long?` (epoch millis, UTC; `null` = not done) to the
   `Task` model. This is a Room schema change → **bump `NeverLateDatabase` version 3 → 4** (see
   *Data Model & Migration*).
2. **Honest stat definitions** (see the table in *Statistics definitions*), all keyed on `completedAt`.
3. **Entry point:** a **stats action icon in the Tasks screen's top app bar** → navigates to Stats as a
   secondary screen (back arrow, bottom bar hidden). *(Settings entry was the alternative considered and
   rejected.)*
4. **Mark-done UX:** a marked-done task **stays in the Tasks list**, shown checked + strikethrough,
   sorted to the bottom, with no live countdown/urgency. Un-checking clears `completedAt` (undo).
5. **Time windows:** ISO week (Monday 00:00 local → next Monday 00:00) + a 24 h due-soon horizon, as
   named constants.
6. **Completion syncs full-stack:** contract-first, then backend column + route mapping, then client +
   sync reconcile.

---

## Statistics definitions (honest, keyed on `completedAt`)

All computed by the pure `weeklyStatsFor(tasks, now, zone)`; `now`/`zone` are parameters (injected from a
`Clock` by the ViewModel) so tests are deterministic. Deleted tasks (`deleted == true`) are excluded from
every count.

| Stat | Definition | Notes |
|---|---|---|
| **Completed this week** (`completedThisWeek: Int`) | Count of tasks with `completedAt != null` and `weekStart ≤ completedAt < weekEnd`. | Real completion — a task counts the week it was *marked done*, regardless of its deadline. |
| **On-time %** (`onTimePercent: Int?`) | Over tasks completed this week (as above) that **have a `deadline`**, the share where `completedAt ≤ deadline`, rounded to nearest int. `null` when there are **no** dated completed-this-week tasks. | Now genuinely "finished before the deadline." Completed tasks with **no** deadline are excluded from this ratio (there was nothing to be on time for) but still count toward *completed this week*. |
| **Due soon** (`dueSoon: Int`) | Count of still-**pending** tasks (`completedAt == null`, `deadline != null`, `deadline > now`) whose deadline is within the next 24 h: `now < deadline ≤ now + DUE_SOON_MILLIS`. | Completed tasks are never "due soon." Reuses the "soon" idea from `urgencyLevelFor`. |

- **Week window:** current ISO week via `java.time` (available on `minSdk 24` through core-library
  desugaring): `weekStart` = Monday 00:00 in the injected `ZoneId`, `weekEnd` = next Monday 00:00.
  Named constants; bounds inclusive/exclusive exactly as written above.
- **`DUE_SOON_MILLIS`** = 24 h, a named constant the lesson points at as "the value a boundary test pins."

---

## Data Model & Migration

### Client (Room) — `Task` + `NeverLateDatabase`

- `Task` (`app/src/main/java/com/neverlate/data/tasks/Task.kt`) gains **`completedAt: Long? = null`**, a
  plain nullable column alongside the existing `deadline`/`updatedAt`/sync fields. `null` = pending;
  non-null = the epoch-millis instant the user marked it done. Purely-local timer fields
  (`timerEndsAt`/`remainingMillis`) stay local and are unchanged.
- `NeverLateDatabase` (`.../data/tasks/NeverLateDatabase.kt`) is **currently `version = 3`** and today
  falls back to **`fallbackToDestructiveMigration(dropAllTables = true)`** (its KDoc: *"Once real users
  have data on-device, every schema change from here on must ship a real Migration."*). Adding
  `completedAt` bumps it **3 → 4**.

**Recommended: ship a real additive `Migration(3, 4)`** —
`ALTER TABLE tasks ADD COLUMN completedAt INTEGER` (nullable, defaults to `NULL`) — registered via
`.addMigrations(...)`, **preserving on-device data**.

- **Why recommend it here (a change from precedent):** the destructive fallback was justified in features
  10/11 because tasks became *backend-owned* — a wiped local cache simply repopulated from the server on
  next login. But **feature 13 (guest mode) broke that assumption**: a guest's tasks live **only
  on-device** (no account, sync inactive), so a destructive migration on app upgrade **permanently loses a
  guest's tasks**. A nullable-column migration is the textbook trivial case, and writing the project's
  **first real `Migration`** is itself excellent lesson material (and pairs naturally with re-enabling
  `exportSchema = true` + a migration test — optional, notable).
- **Consequence if the destructive fallback is kept instead:** upgrading users (and every guest) lose all
  local tasks on first launch after this feature. Flagged in *Risks*; the recommendation is the additive
  `Migration`.

### Backend (Postgres) — additive column

- The backend keeps schema via **`CREATE TABLE IF NOT EXISTS`** in `initSchema`
  (`backend/src/main/kotlin/com/neverlate/backend/db/Database.kt`), whose comment notes backend schema
  changes are **never destructive**. Add the column idempotently:
  **`ALTER TABLE tasks ADD COLUMN IF NOT EXISTS completed_at BIGINT`** (nullable), run once at startup like
  the existing DDL. No data loss; existing rows get `NULL`.
- `PostgresTaskRepository` (`backend/.../tasks/PostgresTaskRepository.kt`): add `completed_at` to
  `SELECT_COLUMNS`, the `INSERT`/`UPDATE` statements, and `ResultSet.toTask()` (as a nullable `Long`, via
  the existing `setNullableLong` / `wasNull()` pattern). The server stays authoritative over `id` and
  `updatedAt`; `completed_at` is **client-provided data**, exactly like `deadline`.

---

## API Contract change (authored FIRST — single source of truth)

`docs/api/contract.md` is authored/changed **before** client or server (its own §Status rule). Time is
**epoch milliseconds (UTC) as a JSON `Long`** (§ intro), so `completedAt` follows `deadline`'s convention.

- **§4 `TaskDto`:** add a row **`completedAt` | `Long?` | Nullable, epoch millis. `null` while the task is
  not done; the instant it was marked complete otherwise. Client-provided (like `deadline`); server is
  authoritative only over `id`/`updatedAt`.** Add `"completedAt": null` to the example JSON.
- **§3 `POST /tasks`:** `completedAt` is an accepted, optional nullable field in the create body (a task
  may be created already-completed in principle; normally `null`).
- **§3 `PATCH /tasks/{id}`:** `completedAt` is an updatable field that must distinguish **omitted**
  (leave unchanged) from **present-and-`null`** (clear → un-complete), i.e. it needs the same
  `PatchValue` treatment the route already gives `deadline`/`estimatedDurationMillis` (see
  `backend/.../common/PatchValue.kt` and the raw-JSON reading in `tasks/TaskRoutes.kt`). Setting or
  clearing `completedAt` **bumps `updatedAt`**, so LWW covers it.
- **§5 Sync semantics:** note `completedAt` travels with the task and is reconciled under the **existing**
  last-write-wins-by-`updatedAt` (delete still wins over edit) — no new rule.
- **No new endpoints, no status-code changes, no auth changes.**

> **Definition-of-Done rule (from CLAUDE.md / the contract):** the contract change and both sides
> (client mapping + server route/DTO/SQL) are reconciled in the **same** branch; they must not drift.

---

## User Stories

### US-1 — Mark a task done and keep seeing it
**As a** person using Never Late,
**I want** to check a task off in my list,
**so that** I record that it's finished without it disappearing.

**Acceptance criteria**
- Each task row has a **mark-done control** (a `Checkbox`/toggle). Checking it sets `completedAt = now`;
  un-checking clears it back to `null` (undo).
- A completed task **remains in the list**, rendered with a **checked box + strikethrough title**, sorted
  **after** all pending tasks, with **no live countdown, no progress bar, and no urgency color**
  (a completed task is never "urgent").
- The change goes through the existing `TaskRepository` save path, writing the task row **and** its
  outbox change row **in the same transaction** (feature 11), so it later syncs like any edit.

### US-2 — See my week at a glance (honest)
**As a** user,
**I want** a statistics screen summarizing my week,
**so that** I get quick, truthful feedback.

**Acceptance criteria**
- Stats show **completed this week**, **on-time %**, **due soon**, computed by `weeklyStatsFor` from the
  observed task list, updating reactively as tasks change or are completed.
- No tasks at all → the shared empty state (`ui/components/MessageState`).
- No dated completed-this-week tasks → on-time % renders a neutral placeholder ("—"), never "0%" or a crash.

### US-3 — Reach statistics without cluttering navigation
**As a** user,
**I want** to open statistics from the Tasks screen,
**so that** the bottom navigation stays focused on Tasks/Articles/Settings.

**Acceptance criteria**
- A **stats action icon** in the Tasks **top app bar** (coherent `contentDescription`) navigates to Stats.
- Stats is a **secondary screen**: back arrow shown (`onBack` non-null), bottom navigation bar hidden
  (Stats is not a top-level route). Back returns to Tasks. Works in **guest mode** (local tasks; no account).

### US-4 — My completion syncs across devices
**As a** signed-in user,
**I want** completing a task on one device to appear on another,
**so that** my task state is consistent.

**Acceptance criteria**
- `completedAt` is carried on `TaskDto` and persisted server-side; a pull (`GET /tasks?since=`) brings a
  peer's completion down.
- Conflicts resolve by **last-write-wins on `updatedAt`** (setting/clearing completion bumps `updatedAt`);
  **delete still wins** over a concurrent completion edit — no special-casing.

### US-5 — (Pedagogical) The logic is testable in isolation
**As a** learner,
**I want** all stats math and sync reconciliation in pure functions with injected time,
**so that** I can unit-test every rule deterministically with no emulator.

**Acceptance criteria**
- All counting/percentage logic lives in one pure function in `domain/tasks/` (new `TaskStats.kt`); no
  statistic is computed inside a Composable.
- The pure function reads no clock/DB/`Context` — `now`/`zone` are parameters.
- The ViewModel does the only impure work (clock read, repository observation) and exposes immutable state
  to a **stateless** screen.

---

## Acceptance Criteria (behavioural, consolidated)

1. `weeklyStatsFor(tasks, now, zone)` → `WeeklyTaskStats(completedThisWeek: Int, onTimePercent: Int?, dueSoon: Int)` per the definitions table.
2. **Deleted** tasks excluded from all three counts.
3. **Completed-but-late** (`completedAt > deadline`) counts toward *completed this week* but as **not on-time** in the ratio.
4. **Completed with no deadline** counts toward *completed this week* and is **excluded** from the on-time ratio.
5. `completedAt == null` = **pending**: eligible for *due soon*, never for *completed*/*on-time*.
6. Boundary behaviour defined and tested: `completedAt` at `weekStart` / `weekEnd`, `deadline` at `now` and `now + 24h`, `completedAt == deadline` (on time), each on the documented side of its comparison.
7. `onTimePercent` is `null` when there are no dated completed-this-week tasks; otherwise a rounded 0–100 int.
8. Marking done/undone persists `completedAt` via `TaskRepository` (task row + outbox row, one transaction) and is reflected in the list and the stats without manual refresh.
9. Completed tasks sort after pending tasks and show no countdown/progress/urgency color.
10. `completedAt` round-trips through `TaskDto` ↔ server ↔ Room; a pulled completion upserts locally; LWW/delete-wins hold.
11. Navigation: the Tasks top-bar stats icon opens Stats (back arrow, bar hidden); back returns to Tasks.

---

## Visual & UX Design

### Master-mockup slice

There is **no statistics slice and no explicit mark-done treatment** in the master mockup
(`docs/mockups/rediseno-ux-ui.html`) or its tracking table (`docs/mockups/README.md`). **The stats screen
is a net-new surface**, recorded as a `—` row (net-new UI, no mockup slice claimed), the way the
feature-03b filter/sort controls are. The **mark-done affordance on the task card** is an addition to the
existing task-card slice; it reuses that card's tokens rather than restyling it.

### Layout (proportionate to a tutorial feature)

- **Stats screen:** branded `TopAppBar` (title "Estadísticas"/"Statistics", `brandedTopAppBarColors()`),
  back arrow; a vertical stack of **three stat cards** (`Card`/`ElevatedCard`) each with a large number
  (type scale, e.g. `headlineMedium`/`displaySmall`), a label (`bodyMedium`), and an optional leading
  `BrandIconChip`. On-time % **may** use the `NeverLateExtras` urgency palette (reused from feature 17) as
  a supporting cue — never as the sole carrier of meaning. Empty state → `MessageState`.
- **Task row mark-done:** a leading (or trailing) `Checkbox` on each `TaskRow` (`TasksScreen.kt`, the
  existing `Card`); completed → `textDecoration = TextDecoration.LineThrough` on the title and a de-emphasized
  container/`onSurfaceVariant` from the theme; the `LinearProgressIndicator` (feature 19) and `colorForUrgency`
  are **omitted** for completed rows.
- **Tasks top bar:** an `IconButton` (e.g. a bar-chart / insights icon) opening Stats.

### Visual acceptance criteria (checked in Design review)

- [ ] Stat numbers use the **Material 3 type scale**; labels a smaller token — no hardcoded sizes.
- [ ] Colors come from **theme tokens** (`MaterialTheme.colorScheme` / `NeverLateExtras`); no literal hex. Correct in **light, dark, and Material You**.
- [ ] Completed task rows show **strikethrough title + checked box** via theme tokens (not a hardcoded gray), and **omit** the progress bar and urgency color.
- [ ] All interactive controls — the row **checkbox**, the top-bar **stats icon**, the **back arrow** — have **≥ 48 dp** touch targets (`minimumInteractiveComponentSize()` where the default is smaller).
- [ ] The stats icon and the checkbox carry coherent `contentDescription`s (e.g. "Mark done"/"Completed"); decorative chips use `contentDescription = null`.
- [ ] Layout **reflows without truncation/overlap at the largest system font scale**; stat cards grow vertically, numbers don't clip.
- [ ] **Empty state** (no tasks) uses `MessageState`, not a column of zeros; on-time % shows "—" when undefined.

### Deferred visual polish

- No charts, sparklines, trends, streaks, or historical comparison — single-number cards only.
- No "completed" filter/section header, no swipe-to-complete gesture — a checkbox toggle only. Any richer
  visual would be a new mockup slice + future feature. Deferring is fine; recorded here so it is not silent.

---

## How the screen is reached (navigation)

**A stats action icon in the Tasks top app bar** navigates to a **`Stats` secondary route inside
`MainAppNavHost`** (`ui/navigation/AppNavHost.kt`) — **not** a bottom-nav tab.

- **Why:** feature 18 route-gates the bottom bar to exactly Tasks/Articles/Settings; a fourth tab would
  dilute the primary navigation. A top-bar action keeps Stats discoverable **from the screen it summarizes**
  while staying a secondary destination (back arrow, bar hidden — consistent with Article Detail / Task
  Edit). Feature 13's separate `Guest`/`LoggedIn` `when` arms are untouched.
- **Alternative considered (rejected):** an entry row in **Settings** — less discoverable and further from
  the tasks it describes.
- **Trade-off:** adds one icon to the Tasks top bar (its busiest chrome); acceptable for a single,
  well-labeled action.

---

## Technical Approach

- **Pure stats function (a test star):** new `domain/tasks/TaskStats.kt` — `data class WeeklyTaskStats(...)`
  + `fun weeklyStatsFor(tasks, now: Long, zone: ZoneId): WeeklyTaskStats`, plain Kotlin + `java.time`, named
  constants, JVM-testable like `ReminderPlanning.kt` / `Urgency.kt`.
- **Sync reconcile (already pure):** `domain/sync/SyncMerge.kt`'s `reconcilePulledTask` needs **no logic
  change** — it carries the whole `TaskDto → Task`, so `completedAt` rides along under LWW. The mapping
  functions (`toNewLocalTask` / `toExistingLocalTask` and the client `TaskDto` in `data/sync`) gain the
  field. Add reconcile **test** bullets covering completion (below).
- **ViewModel + screen:** new `ui/stats/StatsViewModel.kt` (exposes `StateFlow<StatsUiState>`, sealed
  `Loading/Content/Empty` like `TasksUiState`; observes `TaskRepository.observeTasks()`; injects a `Clock`,
  default `Clock.systemDefaultZone()`), and stateless `ui/stats/StatsScreen.kt` + `StatsRoute` wrapper
  (the codebase's `*Screen`/`*Route` split). Reuses theme tokens + `MessageState` + `BrandIconChip` +
  `brandedTopAppBarColors()`.
- **Mark-done:** `TaskRow` gains a checkbox wired to a new `onToggleComplete(task)` callback surfaced by
  `TasksScreen` → `TasksViewModel`, which calls `TaskRepository.saveTask(task.copy(completedAt = ...))`.
  This automatically writes the outbox row in the same transaction (the feature-11 decorator chain). List
  ordering (completed last) and "no countdown/urgency for completed" are handled where the list is shaped
  (`TaskListShaping`/`TasksViewModel`) — a completed task is excluded from the ticking/urgency derivation.
- **Contract + backend:** as in *API Contract change* and *Data Model & Migration* — `TaskDto`,
  `CreateTaskRequest`, PATCH `PatchValue` handling, `PostgresTaskRepository` SQL, and the `ALTER TABLE`.
- **Navigation:** register `Stats` (secondary) in `MainAppNavHost`; add the Tasks top-bar `IconButton`.

### Testing focus (the pedagogical point)

Tests ship **with** the feature (a feature is not done without them) and are the subject of the 04c lesson:

1. **JVM — `TaskStatsTest.kt`** (`app/src/test/.../domain/tasks/`): arrange/act/assert with a fixed
   `now`/`zone`; edge cases — empty list; all pending; deleted excluded; **completed on time**
   (`completedAt ≤ deadline`); **completed late**; **completed with no deadline** (counts, excluded from
   ratio); week boundaries (`weekStart`/`weekEnd`); due-soon boundary (`now + 24h`); `onTimePercent == null`
   (no dated completions → no divide-by-zero). Teaches assertions + why a pure function needs **no fake clock**.
2. **JVM — reconcile — `SyncMergeTest.kt`** (extend existing): pulling a DTO that sets `completedAt` upserts
   it; a newer-`updatedAt` completion overwrites an older local edit while an older one is ignored; a
   tombstone still wins over a pending completion. Shows the **pure sync decision** is trivially testable.
3. **ViewModel — `StatsViewModelTest.kt`** (`app/src/test/.../ui/stats/`): a hand-written **fake
   `TaskRepository`** (pre-11 test-double pattern) emitting a controlled list; `runTest` +
   `StandardTestDispatcher` + a fixed `Clock` → **virtual-time / dispatcher control** over a `StateFlow`
   (`kotlinx-coroutines-test`, already in the catalog).
4. **Compose UI — `StatsScreenTest.kt`** (`app/src/androidTest/.../ui/stats/`): `createComposeRule`, render
   `Content`/`Empty`, assert via `onNodeWithText` / `onNodeWithContentDescription`; **semantics as the test
   surface**; assert the empty state uses `MessageState`.
5. **Compose UI — Tasks mark-done** (extend `TasksScreenTest.kt`): toggling the checkbox invokes
   `onToggleComplete`; a completed row exposes the strikethrough/checked semantics and drops the progress bar.
6. **Backend** (extend `TaskRoutesTest.kt`): create/patch round-trips `completedAt`; clearing it via PATCH
   (present-`null`) un-completes; it appears in a `?since=` pull. *(Optional, notable:* a Room `Migration(3,4)`
   test if `exportSchema` is re-enabled — the payoff of writing a real migration.)*

The lesson closes with **"designing for testability"**: contrasting testing math embedded in a Composable
reading the system clock vs. the pure-function + injected-clock + repository-seam split that makes every
layer deterministic — the same split the reconcile logic already demonstrates.

---

## Out of Scope

- **Charts, graphs, sparklines, trends, streaks, or historical/period comparison** — three single-number cards only.
- **Configurable period** (day/month/custom) or a period switcher — the window is the current ISO week.
- **A fourth bottom-navigation tab** for Stats, or any change to the feature-18 bottom-bar route set.
- **New endpoints** — completion reuses the existing `/tasks` CRUD + `?since=` pull. **No new auth, no new
  refresh/token behaviour, no batched sync endpoint.**
- **New dependencies, permissions, or modules** — test libraries are already in the version catalog.
- **A "completed" filter/section, swipe-to-complete, or bulk complete** — a per-row checkbox toggle only.
- **Syncing live timer state** (`timerEndsAt`/`remainingMillis`) — still local-only, unchanged.
- **New i18n mechanism** — new strings follow the existing `values/` + `values-en/` convention; no new tooling.

---

## Dependencies

**Must already be true (all satisfied today):**
- `TaskRepository.observeTasks()` + the feature-11 outbox/transaction save path — present.
- `Task` fields (`deadline`, `deleted`, sync metadata) and the `TaskDto`↔`Task` mappers in `data/sync` — present.
- Backend `/tasks` CRUD + `?since=` pull, `PatchValue` for nullable-clearable PATCH fields, `PostgresTaskRepository` — present.
- Contract convention (epoch-millis `Long`, server-authoritative `id`/`updatedAt`) — present.
- Test libraries in `gradle/libs.versions.toml`: `junit`, `kotlinx-coroutines-test`, `androidx-ui-test-junit4`, `androidx-ui-test-manifest`, `androidx-junit`, `androidx-espresso-core` — **all present**; **no new dependency**.
- `java.time` on `minSdk 24` via core-library desugaring — enabled (feature 08).
- Theme tokens/components (`ui/theme`, `MessageState`, `BrandIconChip`, `brandedTopAppBarColors()`) and the feature-18 nav seam — present.

**Decisions — all RESOLVED** (see *Resolved decisions*): real `completedAt` (not proxy); Tasks top-bar entry
icon; ISO-week + 24 h constants; mark-done-stays-in-list UX; full-stack sync; **recommended additive
`Migration(3,4)`** over the destructive fallback.

---

## Risks

- **(a) Destructive-migration data loss.** If the current `fallbackToDestructiveMigration(dropAllTables = true)`
  is kept for the 3 → 4 bump, **every user's local tasks are wiped on upgrade — including guest-mode users
  whose tasks exist only on-device (feature 13)**, with nothing to repopulate from. **Mitigation / recommendation:**
  ship a real additive `Migration(3, 4)` (trivial for a nullable column) that preserves data — and gain the
  lesson's first real migration.
- **(b) Contract / backend / client drift.** Completion touches the wire shape, Postgres, Room, and the sync
  mappers at once. **Mitigation:** change `docs/api/contract.md` **first**, reconcile both sides in the same
  branch, and cover the round-trip with backend + reconcile tests (DoD).
- **(c) Scope growth away from the testing lesson.** 04c is now full-stack, which risks the *testing* focus
  getting lost behind plumbing. **Mitigation:** keep testing the spine — the richer surface is *deliberate*
  (more layers to demonstrate: pure functions, coroutine/`Flow`, Compose semantics, backend round-trip). The
  lesson's narrative stays "how we test each layer," not "how we built completion."
- **(d) Timezone / week-boundary correctness** (the `deadlineFromPickedDateTime` bug class). **Mitigation:**
  `zone` is an explicit parameter, boundaries pinned by unit tests.
- **(e) "On-time" semantics for edge tasks** (no-deadline completions, `completedAt == deadline`). **Mitigation:**
  defined explicitly in ACs 3–4 & 6 and pinned by tests.

---

## Definition of Done (workflow checklist)

- [ ] Spec approved (behaviour **and** look — this document).
- [ ] **`docs/api/contract.md` updated first**: `completedAt` on `TaskDto` (§4), create/PATCH (§3, `PatchValue`), sync note (§5); example JSON updated.
- [ ] **Backend**: `ALTER TABLE tasks ADD COLUMN IF NOT EXISTS completed_at BIGINT` in `initSchema`; `completed_at` in `PostgresTaskRepository` (`SELECT_COLUMNS`, INSERT/UPDATE, `toTask()`); `TaskDto`/`CreateTaskRequest`/PATCH mapping carry `completedAt`; backend `TaskRoutesTest` round-trip.
- [ ] **Room**: `Task.completedAt`; `NeverLateDatabase` 3 → 4 with a **real additive `Migration(3,4)`** (recommended) — policy chosen and stated; `data/sync` mappers carry `completedAt`.
- [ ] **Client UI**: Tasks-row mark-done checkbox (strikethrough + checked, completed sorts last, no countdown/urgency); Tasks top-bar stats icon; `Stats` secondary route in `MainAppNavHost`.
- [ ] **Stats**: pure `weeklyStatsFor` + `WeeklyTaskStats`; `StatsViewModel` (+ state); stateless `StatsScreen`/`StatsRoute`.
- [ ] **Tests**: `TaskStatsTest` (honest edge cases); `SyncMergeTest` completion cases; `StatsViewModelTest` (`runTest`); `StatsScreenTest` + Tasks mark-done UI test.
- [ ] Visual ACs verified in the running app; `docs/mockups/README.md` updated (net-new `—` Stats row; note the mark-done addition to the task-card slice).
- [ ] Spanish lesson `tutorial/04c-testing-estadisticas.md`; `tutorial/README.md` + `docs/conceptos-pendientes.md` §3 updated (04c → ✅).
- [ ] New strings in `values/strings.xml` + `values-en/strings.xml`.
- [ ] Committed on `feature/stats-testing` (never on `master`).

---

## Approval request

Please review and **approve or adjust** before implementation. Approval covers **behaviour and look**. The
six *Resolved decisions* are recorded as decided; the one point still worth an explicit nod is **Risk (a)**:
this spec **recommends a real additive `Migration(3,4)`** (preserving data, especially guest-mode tasks)
over keeping the destructive fallback — please confirm that recommendation stands.
