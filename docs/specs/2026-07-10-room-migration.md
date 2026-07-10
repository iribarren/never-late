# Feature 13b — Real Room migration + `TypeConverter`

**Status:** ✅ Approved 2026-07-10 (OD-1 = `priority` enum; OD-2 = sync end-to-end; visual approach + build-config signed off). In implementation on `feature/room-migration`.
**Date:** 2026-07-10
**Proposed branch:** `feature/room-migration`
**Tutorial slot:** `13b` (between feature 13 and feature 14), per `docs/conceptos-pendientes.md` §6 and
`tutorial/README.md`. Lesson file: `tutorial/13b-migraciones-room.md`.
**Backlog mapping:** `docs/conceptos-pendientes.md` §6 (Datos: migraciones) — "profundizar en lo que 04c no
tocó: `TypeConverter`, `AutoMigration`, y tests de migración con `exportSchema` activado".

---

## Overview

Feature 04c shipped this project's **first real, data-preserving Room migration** (`MIGRATION_3_4`,
`ALTER TABLE tasks ADD COLUMN completedAt INTEGER`), but it was deliberately the *easy* case: a nullable
column of a primitive type that Room already knows how to store. This feature goes **deeper** into the
data layer's migration story — the concepts 04c left untouched:

1. A new `Task` field of a type Room **cannot** persist natively, forcing a **`@TypeConverter`**.
2. A second explicit **`Migration(4, 5)`** that adds a **`NOT NULL` column with a `DEFAULT`**, so existing
   rows survive the version bump with a sensible value.
3. **Schema export** (`exportSchema = true` + `room.schemaLocation`) and a **migration test** with
   `MigrationTestHelper` that proves real data survives the 4 → 5 jump.
4. A didactic contrast between a **manual `Migration`** and an **`AutoMigration`**, explaining which this
   feature uses and why.

Concretely, `Task` gains a **`priority: Priority`** enum (an app-level concept: how important/urgent the
user considers a task). Because Room has no column type for an arbitrary enum, this is exactly what
compels the `TypeConverter` — the core teaching goal. The field is surfaced in the **task edit UI** (a
priority selector) and hinted on the **task list card** (a small, theme-tokened indicator).

This is a **teaching feature** first: the app gains a modest, genuinely useful field, but the point is the
Room migration + converter + migration-test machinery it lets us teach cleanly.

> **Field choice and sync are Open Decisions** (see the dedicated section). The spec's default
> recommendation is: field = **`priority` enum**, and it **syncs end-to-end** (mirroring how 04c handled
> `completedAt`). Both need the user's explicit sign-off before implementation.

---

## Goals

- Teach a **real, additive, data-preserving `Migration(4, 5)`** that adds a `NOT NULL … DEFAULT` column
  (a step up from 04c's nullable-column case), with **zero data loss** for existing (including guest-mode
  on-device-only) tasks.
- Teach **`@TypeConverter` / `@TypeConverters`** for a type Room can't store natively — an **enum** —
  extending the existing `Converters` class rather than inventing a parallel mechanism.
- Turn on **schema export** and teach **migration testing** with `MigrationTestHelper`, so the migration
  is *verified* (real 4 → 5 data survival), not just asserted in prose.
- Teach the **`Migration` vs `AutoMigration`** decision explicitly, and why `fallbackToDestructiveMigration`
  is unacceptable as a production upgrade path.
- Ship the field through the **existing** data + sync + UI seams (extend `Task`/`TaskDao`/`NeverLateDatabase`/
  the outbox/`TaskDto`) with **no** break to sync metadata or the last-write-wins reconcile.
- Add a small, honest UI slice (edit selector + list indicator) using **existing theme tokens**, with
  deferred polish stated explicitly.

Success looks like: an existing user upgrades from DB v4 to v5, **keeps every task**, sees each one default
to `Priority.NONE`, can set a priority in the edit screen, sees it reflected on the list, and (if the
sync decision is "yes") the priority round-trips through the backend across devices — all covered by a
passing `MigrationTestHelper` test.

---

## User Stories

### US-1 — Set a task's priority
**As a** user with ADD/ADHD juggling many tasks,
**I want** to mark how important each task is (e.g. None / Low / Medium / High),
**so that** I can tell at a glance which tasks matter most.

**Acceptance criteria**
- The task edit screen shows a **priority selector** with all `Priority` values; the current value is
  pre-selected (defaulting to `NONE` for a new task and for any task migrated from v4).
- Selecting a priority and saving persists it through the **normal** `saveTask` path (same outbox /
  transaction machinery as any other field edit — no special-casing).
- Re-opening the task shows the persisted priority.
- Priority is **not** part of the "at least duration or deadline" domain rule; a task with only a priority
  set is still invalid unless it also has a duration or deadline (rule unchanged).

### US-2 — See priority on the task list
**As a** user scanning my task list,
**I want** a lightweight visual indicator of each task's priority,
**so that** I can spot high-priority tasks without opening them.

**Acceptance criteria**
- Each task row shows a **small priority indicator** for non-`NONE` priorities (see *Visual & UX Design*
  for the exact treatment and what's deferred).
- `Priority.NONE` shows **no** indicator (no visual noise for the default case).
- The indicator uses existing theme tokens (`NeverLateExtras` / Material 3 roles), not one-off colors.
- Completed tasks keep their existing muted treatment; the priority indicator does not override the
  done-state styling (consistent with how 04c's completed rows drop urgency color).

### US-3 — Upgrade without losing tasks (the migration)
**As an** existing user (including a guest whose tasks live only on-device),
**I want** the app to keep all my tasks when it updates to the new schema,
**so that** an app update never wipes my data.

**Acceptance criteria**
- `NeverLateDatabase.version` is bumped **4 → 5** with an explicit **`MIGRATION_4_5`** registered via
  `addMigrations(...)` (alongside the existing `MIGRATION_3_4`).
- `MIGRATION_4_5` runs `ALTER TABLE tasks ADD COLUMN priority TEXT NOT NULL DEFAULT '<default>'` (the stored
  form of `Priority.NONE`), so every pre-existing row gets a valid, non-null value.
- A **`MigrationTestHelper` test** creates a v4 database with representative task rows, runs the migration
  to v5, and asserts: (a) all rows still exist, (b) their prior fields are unchanged, (c) `priority`
  reads back as the default. The test uses the **exported schema JSON** (see US-4).
- `fallbackToDestructiveMigration` remains registered **only** as a last-resort safety net (unchanged
  policy from 04c) — it is **not** the path taken for the 4 → 5 upgrade.

### US-4 — Verifiable migrations (schema export + tooling)
**As a** developer/learner following this tutorial,
**I want** Room to export its schema and a test that migrates real data,
**so that** I can see how migrations are proven correct rather than hoped-correct.

**Acceptance criteria**
- `@Database(... exportSchema = true)` and the KSP `room.schemaLocation` argument are configured so Room
  writes versioned schema JSON (`app/schemas/…/4.json`, `5.json`) checked into the repo.
- The `androidx.room:room-testing` artifact (`MigrationTestHelper`) is available to the test source set
  (added to `gradle/libs.versions.toml` if not already present — see *Dependencies*).
- The migration test runs green locally (instrumented or Robolectric-backed per the project's existing
  Room-test approach — see *Technical Approach*).

### US-5 — Priority survives sync *(applies only if the sync Open Decision is "yes")*
**As a** user with more than one device (or who reinstalls and logs back in),
**I want** a task's priority to travel with it through the backend,
**so that** priorities aren't a per-device secret.

**Acceptance criteria**
- `TaskDto` gains a `priority` field in `docs/api/contract.md` (updated **first**, per the contract rule),
  with defined default/absent handling; the Android `TaskDto ↔ Task` mapping carries it both ways.
- The backend persists a `tasks.priority` column and echoes it on create/pull/patch; it reconciles under
  the **existing** last-write-wins-by-`updatedAt` rule with **no** new sync logic (exactly the pattern
  04c used for `completedAt`).
- Client and server default an **absent/unknown** priority to `NONE` for forward-compatibility.

---

## Technical Approach

High-level strategy — **extend, don't duplicate** the existing data layer.

### Data model (`app/src/main/java/com/neverlate/data/tasks/`)
- **New enum** `Priority` (e.g. `NONE, LOW, MEDIUM, HIGH`) — a small `enum class`, likely a new file in
  `data/tasks/` (or co-located with `Task`), mirroring how `SyncState` lives beside the entity.
- **`Task`** gains `val priority: Priority = Priority.NONE` (non-null with a Kotlin default, matching the
  SQL `DEFAULT`). It stays a plain data holder; validation is untouched (`priority` is not part of the
  "duration or deadline" rule).
- **`Converters`** (`data/sync/Converters.kt`) gains a symmetric pair
  `fromPriority(Priority): String = value.name` / `toPriority(String): Priority` that **tolerantly**
  falls back to `Priority.NONE` on an unknown value — reusing the exact pattern already there for
  `SyncState`/`OutboxOperation` (store `Enum.name` as `TEXT`, resilient to reordering, crash-proof on
  unknown values for forward-compat). No new `@TypeConverters` registration is needed (the class is
  already registered on `NeverLateDatabase`).

### Migration + schema (`NeverLateDatabase.kt`, `app/build.gradle.kts`)
- Bump `@Database(version = 4 → 5)` and flip **`exportSchema = false → true`**.
- Add `room.schemaLocation` to the app module's KSP annotation args in `app/build.gradle.kts` (e.g.
  `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`), and commit the generated `4.json` /
  `5.json`. **Note:** with export enabled, Room needs a `4.json` baseline to migrate *from*; generating it
  cleanly may require a one-time build at v4 with export on, or hand-placing the baseline — call out in the
  lesson.
- Add `MIGRATION_4_5` (an anonymous `Migration(4, 5)`) doing
  `ALTER TABLE tasks ADD COLUMN priority TEXT NOT NULL DEFAULT '<Priority.NONE.name>'`, and register it via
  `.addMigrations(MIGRATION_3_4, MIGRATION_4_5)`.
- **Manual `Migration` vs `AutoMigration`:** this feature uses a **manual `Migration`** (didactically
  richer — the learner writes the SQL and sees exactly what happens; and adding a `NOT NULL DEFAULT`
  column is a case worth writing by hand). The lesson **contrasts** it with `AutoMigration` (Room
  generates the SQL from the exported schema diff for simple additive changes) and explains the trade-off
  and when each is appropriate — but we intentionally hand-write this one to teach the mechanics.

### Sync (only if the sync decision is "yes")
- Update `docs/api/contract.md` **first** (`TaskDto` table + create/patch examples + a note in §5 that
  `priority` rides the existing LWW rule, exactly like `completedAt`).
- Extend the Android sync `TaskDto` (`data/sync/`) + its `TaskDto ↔ Task` mapping.
- Backend: add a `tasks.priority` column + wire it through create/pull/patch, defaulting absent/unknown to
  `NONE`. No new endpoint, no new sync rule.
- **DB-version-only impact:** if sync is "no", the change is entirely local (no contract/backend edits) —
  see *Open Decisions*.

### UI (`app/src/main/java/com/neverlate/ui/tasks/`)
- **`TaskEditScreen` / `TaskEditViewModel`:** add a priority selector (see *Visual & UX Design*), hoisted
  as state like the other form fields; wire it into the existing form-state → `saveTask` flow.
- **`TasksScreen` (task card):** render a small priority indicator per row for non-`NONE` priorities.

### Testing (`qa-engineer`)
- **Migration test** with `MigrationTestHelper` (the headline new test) — v4 → v5 data survival.
- **`Converters` unit tests** for the new `Priority` pair, including the unknown-value → `NONE` fallback.
- UI/VM coverage that a chosen priority persists and re-hydrates; list indicator reflects the value.
- Follow the project's **existing Room-test approach** (Robolectric is already in the catalog for real
  Room transactions; `MigrationTestHelper` historically runs instrumented — confirm during implementation
  which source set it lands in, `test` vs `androidTest`, and note it in the lesson).

---

## Visual & UX Design

### Mockup slice
The master mockup (`docs/mockups/rediseno-ux-ui.html`) has **no priority element** (confirmed — no
priority/prioridad markup in the mockup). So, like the Tasks filter controls (03b) and the Stats screen
(04c), this feature claims **no mockup slice**; the priority UI is **net-new** and will be tracked in
`docs/mockups/README.md` as a `—` (net-new, not a mockup element) row for context, not as a pending
north-star slice. The Design review step must add that row.

Because there's no north-star reference for priority, the UI must lean **entirely** on existing theme
tokens and components so it reads as part of the same system.

### Visual acceptance criteria
- **Edit selector:** priority is chosen via a set of Material 3 **`FilterChip`s** in a reflowing `FlowRow`
  (reusing the exact pattern already used by the Tasks filter/sort controls from 03b) — one chip per
  `Priority`, single-select, the active one visually selected. Touch targets **≥ 48dp**
  (`minimumInteractiveComponentSize()`), consistent with the a11y pass. (Chips are preferred over a
  dropdown for discoverability and to reuse an existing primitive; a dropdown is an acceptable alternative
  if the engineer finds it cleaner — call it out at review.)
- **List indicator:** non-`NONE` priority shows a **small, subtle** indicator on the task row — a compact
  leading/trailing dot or label — colored from **existing tokens only**. Reuse `NeverLateExtras` /
  Material 3 container roles; **do not** invent new colors. `Priority.NONE` shows nothing.
- Each priority chip/indicator has a coherent **`contentDescription`** (localized string resource), or is
  marked decorative (`contentDescription = null`) where a redundant text label already conveys it.
- Layout **reflows** at the largest font scale without truncation or overlap (the `FlowRow` handles chip
  wrapping).
- Completed-task styling wins: a done task keeps its strikethrough/muted treatment and does **not** show a
  loud priority color (consistent with 04c dropping urgency color on completed rows).
- All user-facing text (chip labels, indicator descriptions) lives in **string resources**
  (`values/` Spanish base + `values-en/` English), per feature 08 i18n.

### Explicitly deferred polish
- **No** priority-based sorting/filtering/grouping on the Tasks list (that would extend 03b's
  `TaskListShaping`; out of scope here to keep the lesson focused on migration + converter).
- **No** color-coded urgency blending priority with the countdown urgency colors (17/19) — a richer
  visual language for "priority × urgency" is deferred to a future UI feature.
- **No** priority in the widget (05), lock-screen notification (06), reminders (09), or Stats (04c)
  surfaces. If any is desired later, it's a separate slice.
- **No** custom iconography beyond reused chips/dots.

These deferrals are stated so the gap is owned, not silent (per *Design in the Workflow*).

---

## Out of Scope

- Any second new field beyond the one chosen in *Open Decisions* (`priority` **or** `notes`, not both).
- Priority-driven list behaviour: sorting, filtering, grouping, or reminder prioritization.
- Priority on non-edit/non-list surfaces (widget, notification, reminders, stats).
- `AutoMigration` as the *shipped* mechanism (it's **taught by contrast** in the lesson, but this feature
  hand-writes a manual `Migration`).
- Retroactively adding schema export / migration tests for the earlier 1→2→3 (destructive) jumps — export
  starts at the v4 baseline going forward.
- A production HTTPS backend / release build (still tracked for feature 21).
- De-duplication or backfill of priority for tasks created before this feature (all pre-existing rows
  simply default to `NONE`).

---

## Dependencies

**Must be true before implementation:**
- **Open Decisions resolved** (field choice + sync) — these change scope (contract/backend edits happen
  only if sync = yes).
- The existing `Converters` class and `NeverLateDatabase` migration wiring (present — verified in
  `data/sync/Converters.kt` and `NeverLateDatabase.kt`, which already ships `MIGRATION_3_4` at v4).

**Build-config changes required (call-outs, verify during implementation):**
- **`androidx.room:room-testing`** for `MigrationTestHelper` — **not currently in
  `gradle/libs.versions.toml`** (the catalog has `room-runtime`/`room-ktx`/`room-compiler` at `room = "2.7.1"`,
  plus Robolectric for existing Room-transaction tests, but **no** `room-testing`). It must be **added** to
  the version catalog (reuse the existing `room` version ref) and wired into the appropriate test source
  set. This is the one likely **new dependency**; flag it in the doc-check.
- **Schema export must be turned on**: `exportSchema = true` on `@Database` **and** the
  `room.schemaLocation` KSP arg in `app/build.gradle.kts` (currently `exportSchema = false` and no schema
  location) — otherwise `MigrationTestHelper` has no schemas to migrate between. Commit the generated
  `4.json` / `5.json`.

**Contract / backend (only if sync = yes):**
- `docs/api/contract.md` updated **first** (contract is the single source of truth), then Android + backend
  reconciled to it; a Postgres `tasks.priority` column added on the backend.

**Documentation (mandatory before commit, per CLAUDE.md):**
- `tutorial/13b-migraciones-room.md` (Spanish lesson) — fill the reserved `13b` slot; flip its status to ✅
  in both `docs/conceptos-pendientes.md` and `tutorial/README.md`.
- `docs/mockups/README.md` — add the net-new priority-UI row (Design review step).
- `CLAUDE.md` — update the data-layer / feature-history notes (DB now v5, `MIGRATION_4_5`, `Priority` +
  its converter, schema export enabled) and the *New dependency* / *permission* checklist items as they apply.
- `gradle/libs.versions.toml` — the `room-testing` entry (per *Dependencies*).

---

## Open Decisions (need user sign-off before implementation)

> **RESOLVED (2026-07-10):** OD-1 → **`priority` enum**. OD-2 → **syncs end-to-end** (contract + backend
> `tasks.priority` column, US-5 in scope). Details below preserved for rationale.

These are the either/or choices the workflow requires the user to confirm. The spec's recommendation is in
**bold**, with the trade-off.

### OD-1 — New field: `priority` enum **vs** `notes` string
- **Recommended: `priority: Priority` enum.** An enum is a type Room **cannot** persist natively, so it
  *forces* the `@TypeConverter` — which is the entire point of this lesson. It also gives a clean, small
  UI (chips + indicator) and an unambiguous default (`NONE`) for migrated rows.
- **Alternative: `notes: String`.** Simpler and also useful, **but** `String` is a primitive Room already
  stores directly, so it would **not** exercise a `TypeConverter` at all — it would essentially repeat
  04c's "add a column" lesson without the new concept. Rejected as the primary field for that reason
  (though a `notes` field is a fine candidate for a *future* feature).
- **Decision needed:** confirm `priority` enum, or override to `notes` (which would require re-scoping the
  lesson to keep a converter-teaching angle — e.g. store `List<String>` tags — since the migration alone
  repeats 04c).

### OD-2 — Does `priority` sync end-to-end, or stay local-only?
- **Recommended: syncs end-to-end** (update `TaskDto` in the contract + a backend `tasks.priority` column),
  mirroring exactly how 04c handled `completedAt`. "Does the new field sync?" is precisely the deliberate
  decision this lesson should make and document; syncing keeps the field consistent across devices and
  reuses the existing LWW reconcile with **no** new sync logic.
- **Alternative: local-only** (like the timer fields `timerEndsAt`/`remainingMillis`, which are
  deliberately not synced). This keeps the feature entirely within the app (no contract/backend edits,
  smaller blast radius, faster) at the cost of priorities being per-device. Justifiable if we want to keep
  13b tightly scoped to *migration mechanics* and leave sync teaching to features that already own it.
- **Decision needed:** confirm **sync end-to-end** (adds a contract change + backend column, and enables
  US-5), or choose **local-only** (drops US-5 and all contract/backend work; the DB-version bump, migration,
  converter, and UI stay identical).

> Note on scope coupling: OD-2 = "local-only" removes the entire contract/backend workstream; the Room
> migration, `TypeConverter`, schema export, migration test, and UI are **unaffected** either way — so the
> core teaching goals hold under both choices.

---

## Please review and approve

Per the New Feature Workflow, implementation does **not** start until you approve this spec — and approval
covers **both** the behaviour **and** the look (the *Visual & UX Design* section). Specifically, please
confirm:

1. **OD-1:** `priority` enum (recommended) or `notes` string?
2. **OD-2:** priority **syncs end-to-end** (recommended) or **local-only**?
3. The **visual approach** (FilterChips in the edit screen + a subtle token-based list indicator, no
   mockup slice claimed, sorting/filtering by priority deferred).
4. That adding **`androidx.room:room-testing`** and enabling **schema export** are acceptable build-config
   changes.

Once you confirm, next steps are: create `feature/room-migration`, implement to the visual **and**
behavioural acceptance criteria (`mobile-engineer`), add tests incl. the `MigrationTestHelper` migration
test (`qa-engineer`), run the Design review + update `docs/mockups/README.md`, write
`tutorial/13b-migraciones-room.md`, then commit on the branch.
