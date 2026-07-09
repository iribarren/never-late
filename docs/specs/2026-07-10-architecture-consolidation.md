# Feature 07b (extra) — Arquitectura: poner nombre al patrón (UDF / MVVM / capas)

- **Status:** Draft — awaiting approval
- **Branch (suggested):** `feature/architecture-consolidation`
- **Tutorial lesson:** `tutorial/07b-arquitectura.md` (Spanish, numbered **07b**, interleaved between 07 and 08)
- **Roadmap:** `docs/conceptos-pendientes.md` §4 (Arquitectura)
- **Type:** Cross-cutting **teaching/consolidation** lesson. The deliverable is almost entirely documentation — a Spanish lesson that *names, diagrams, and consolidates* an architecture the app has used since feature 02 but never named. Any code change is a **minimal, coherence-driven alignment with no observable behavior change**.

---

## Overview

Since feature 02 the app has followed **UDF (unidirectional data flow) + MVVM** on top of a **layered
architecture** (UI / domain / data) — `ViewModel` + `StateFlow` exposing immutable UI state, stateless
Composables, pure domain functions, and repository seams that let sync/reminder decorators wrap without
touching the UI. The pattern is real and consistent, but **no lesson has ever named it**. A learner
following the tutorial has absorbed the shape by imitation without the vocabulary or the mental model.

Feature 07b closes that gap. It is a **transversal consolidation lesson**: it puts a name to what already
exists, draws the layers/seams diagram, and points at concrete existing code as the canonical example of
each concept. It sits at **07b** because by feature 07 the learner has seen all three layers in action —
UI (Compose from 02–03), domain (pure functions from 04), and data (Room from 04, DataStore from 07) —
so the pattern can finally be abstracted from lived examples rather than introduced in the abstract.

**This is more didactic than product-facing.** The explicit constraint, from the roadmap and the feature
prompt, is **extend, don't duplicate**: the goal is to *document* the architecture in place, **not** to
introduce a new one or refactor toward one. The app is expected to already be largely coherent. Therefore
the "new code" is, at most, a handful of small alignments that surface *during* the review — never a
promised big change.

---

## Goals

1. **Name the pattern.** Give the learner the vocabulary — UDF, MVVM, UI/domain/data layers, the "seam" —
   and tie each term to code they have already written, so the abstraction lands on familiar ground.
2. **Diagram the architecture.** Ship a clear layers-and-flow diagram (state down, events up; UI → domain
   → data; where the seams sit) in the Spanish lesson, using the app's real types as the labels.
3. **Consolidate, don't refactor.** Review the existing UI ↔ domain ↔ data seams for coherence. Apply a
   coherence alignment **only if** a genuine inconsistency surfaces (see *Candidate alignments*), and only
   when it can be done with **no observable behavior change**.
4. **No behavior change, tests stay green.** Whatever ships must leave the app behaving identically and
   the existing JVM + Compose test suites passing unchanged.

Success looks like: a learner can read `07b-arquitectura.md`, point at `TasksViewModel`,
`ReminderPlanning.kt`, `TaskRepository`, and the sync merge in `domain/sync/` and correctly say *which
layer each belongs to and why* — and the app builds, runs, and tests exactly as before.

---

## What this feature is (and is not)

| | |
|---|---|
| **Is** | A Spanish tutorial lesson that names + diagrams + explains the existing UDF/MVVM/layered architecture, anchored to real code. |
| **Is** | A coherence *review* of the existing UI/domain/data seams. |
| **May be** | One or a few *minimal* alignments if an inconsistency is found — behavior-preserving only. |
| **Is not** | A refactor, an architecture change, or a migration to a new pattern (that would be Hilt/DI — lesson **13d**, out of scope here). |
| **Is not** | Any change to observable behavior, backend, API contract, DB version, dependencies, or permissions. |

---

## Concepts the lesson must teach (Spanish `tutorial/07b-arquitectura.md`)

The lesson is the primary deliverable. It must teach, each anchored to existing code the learner has met:

- **UDF (unidirectional data flow).** State flows **down** (ViewModel → Composable via `StateFlow` →
  immutable UI state), events flow **up** (Composable callbacks → ViewModel). Why the UI is a *function
  of state*, and why that makes it predictable and testable. Anchor: any screen's
  `ViewModel` → `collectAsStateWithLifecycle` → stateless Composable + hoisted callbacks.
- **MVVM on Android.** The role of the `ViewModel` (survives config changes, owns `viewModelScope`),
  `StateFlow` as the single source of screen state, and **stateless UI** via state hoisting. Anchor:
  `TasksViewModel` and its `StateFlow` UI-state, especially the reactive `debounce`/`combine`/`stateIn`
  pipeline from feature 04b as the mature example.
- **UI / domain / data layers — what lives where and why.**
  - **UI:** Jetpack Compose (Material 3), stateless where possible — `ui/screens`, `ui/components`.
  - **Domain:** pure, framework-free, JVM-testable logic — e.g. `ReminderPlanning.kt`
    (`reminderTimeFor`, `remindersToSchedule`), `urgencyLevelFor`, `deadlineProgressFor`,
    `TaskListShaping.kt` (`shapedBy`), `weeklyStatsFor`, and the sync **reconcile/merge** in
    `domain/sync/`. Why the domain is kept **pure** (no Android, no I/O) → deterministic and cheap to test.
  - **Data:** repositories, Room, DataStore, network — `data/tasks`, `data/articles`, `data/sync`,
    `data/auth`. The source of truth (Room) and the wire shapes (`TaskDto`, `ArticleDto`).
- **The "seam" concept.** An interface (`TaskRepository`) that lets you **decorate/inject** behavior
  without touching the UI. Anchor: the already-shipped decorator stack —
  `ReminderSchedulingRepository` and `TaskSurfacesRefreshingRepository` composed in `MainActivity`,
  and sync/auth entering behind the same `TaskRepository` seam (feature 11). This is *why* the app can
  add reminders and sync without the Tasks screen knowing they exist — and it foreshadows lesson **13d**
  (Hilt) as "automating the wiring we do by hand today."

The lesson should explicitly reference the earlier lessons it consolidates (02 `ViewModel`/`StateFlow`,
04 repos + pure functions, 07 DataStore) and forward-reference **13d (Hilt)** as the natural next step for
the DI wiring shown here.

---

## User Stories

### US-1 — Learner names the pattern
**As a** learner following the Never Late tutorial,
**I want** a lesson that names and diagrams the architecture the app has used all along (UDF / MVVM /
layers),
**so that** I can reason about *where new code belongs* instead of copying the shape by imitation.

**Acceptance criteria**
- `tutorial/07b-arquitectura.md` exists, is in Spanish, and is numbered/titled per the `07b` slot.
- It defines UDF, MVVM, the three layers, and the seam concept, each tied to a **named existing symbol
  or file** in this codebase (not a generic textbook example).
- It contains a **layers/flow diagram** (state down / events up; UI → domain → data; seams marked).
- It references prerequisite lessons (02, 04, 07) and forward-references 13d (Hilt).

### US-2 — Maintainer trusts coherence
**As a** maintainer,
**I want** the existing UI/domain/data seams reviewed for coherence while we document them,
**so that** the documented pattern and the real code actually match.

**Acceptance criteria**
- The review checks the *Candidate alignments* checklist below across the current screens/domain/data.
- If **no** inconsistency is found, **no production code changes** — the lesson ships alone, and that is
  a valid, complete outcome.
- If an inconsistency **is** found, it is fixed with a **minimal, justified** change that a reviewer can
  see is behavior-preserving, and the lesson uses the before/after as a teaching example.

### US-3 — Nothing regresses
**As a** user of the app,
**I want** this lesson to change nothing about how the app behaves,
**so that** a documentation change never risks my tasks, reminders, or sync.

**Acceptance criteria**
- No observable behavior change on any screen or background surface.
- Existing JVM (`:app:testDebugUnitTest`) and, where relevant, Compose UI tests pass **unchanged** — no
  test is modified to accommodate a behavior change (only additive tests, if any, are allowed).
- No backend, API-contract, DB-version, dependency, or permission change.

---

## Acceptance Criteria (consolidated)

**Behavioural**
- **AC-1 (no behavior change).** The app behaves identically before and after this feature on every
  surface (Tasks, Articles, Settings, Stats, widget, notifications, reminders, sync). This is the
  headline criterion.
- **AC-2 (tests stay green).** The existing test suites pass **without modification** to accommodate a
  behavior change. Existing tests are not weakened or deleted. Any new tests are purely additive and
  characterize behavior that is already true.
- **AC-3 (no cross-cutting change).** No change to `backend/`, `docs/api/contract.md`,
  `NeverLateDatabase` version, `gradle/libs.versions.toml`, or `AndroidManifest.xml` permissions.
- **AC-4 (build stays green).** `./gradlew :app:assembleDebug` and `:app:testDebugUnitTest` succeed.

**Documentation (the deliverable)**
- **AC-5.** `tutorial/07b-arquitectura.md` ships, in Spanish, teaching UDF, MVVM, the three layers, and
  the seam — each anchored to a real symbol/file in this repo.
- **AC-6.** The lesson includes a layers/architecture diagram (state down / events up; UI → domain →
  data; seams marked). Mermaid is acceptable if it renders; otherwise a clear ASCII/text diagram.
- **AC-7.** `tutorial/README.md` flips the **07b** row from 🚧 to ✅, and
  `docs/conceptos-pendientes.md` §4 marks the "arquitectura nombrada → 07b" item as done. No lesson is
  renumbered.

**Coherence alignment (conditional)**
- **AC-8.** *If* a coherence alignment is made, it is minimal and justified in the commit/lesson, and
  demonstrably behavior-preserving (covered by AC-1/AC-2). *If not*, "no code change beyond the lesson"
  is an explicitly acceptable outcome and satisfies this feature.

---

## Candidate alignments to look for (review checklist — not a promise to change)

The review scans for genuine incoherences of these kinds. Each is a *candidate*; most may already be
clean. **Only** clear, behavior-preserving fixes qualify — anything that would alter output, timing, or
structure beyond a rename/move is deferred out of scope.

1. **UI state shape.** A screen exposing loose individual `StateFlow`s / raw fields where the established
   pattern is a single immutable UI-state type — align to the prevailing pattern only if trivial and
   behavior-identical.
2. **Calculation in the wrong layer.** A non-trivial pure calculation living inside a `ViewModel` or a
   Composable that clearly belongs in `domain/` next to `urgencyLevelFor` / `weeklyStatsFor` — extract to
   a pure function **only if** it is a mechanical move with identical results (and gains a JVM test).
3. **Naming/placement drift.** A domain-ish helper sitting under `data/` or `ui/` (or vice versa) that a
   pure move to its correct layer would clarify — package/location only, no logic change.
4. **Stateful UI that could be hoisted.** A Composable holding state that the established hoisting pattern
   would lift to the ViewModel — only if behavior is provably unchanged.

If a candidate would require touching behavior, timing, the DB, the contract, or a dependency, it is
**not** in scope for 07b — note it as future work instead.

---

## Technical Approach

1. **Review pass (read-only first).** Walk the current UI/domain/data seams — `ui/screens`, `ui/*`
   feature packages, `domain/tasks`, `domain/sync`, `data/*`, and the repository decorator wiring in
   `MainActivity` — against the *Candidate alignments* checklist. Produce the list of anchor examples the
   lesson will cite. No edits in this pass.
2. **Alignments (only if surfaced).** Apply at most a few minimal, behavior-preserving changes from the
   checklist. Each is an extract-to-pure-function or a move/rename, verified against existing tests
   (AC-2) and, for an extracted pure function, given an additive JVM test.
3. **Write the lesson.** `tutorial/07b-arquitectura.md` (Spanish): concepts + diagram + anchored code
   walk-through, referencing lessons 02/04/07 and forward-referencing 13d. If an alignment was made, use
   its before/after as the concrete teaching moment.
4. **Update tracking.** Flip 07b to ✅ in `tutorial/README.md` and mark the item done in
   `docs/conceptos-pendientes.md` §4.

No new sub-project, module, package (unless a checklist move *is* a package move), or dependency.

---

## Visual & UX Design

**Master-mockup slice: N/A — no visual change.** This is an architecture/documentation lesson and ships
**essentially no new UI**. It delivers **no** slice of the master mockup
(`docs/mockups/rediseno-ux-ui.html`) and **defers none** — there is simply no visual surface in scope.

- **Visual acceptance criterion:** the rendered UI is **pixel-identical** before and after. If a coherence
  alignment is made (e.g. hoisting state or extracting a calculation), it must **not** alter what any
  screen renders — same layout, same content, same states.
- **Mockup tracking (`docs/mockups/README.md`) needs no update** for this feature: no mockup element moves
  from pending/partial to done, and nothing new is deferred, because there is no visible UI change to
  record. (The Design-review workflow step is satisfied by confirming exactly this — "no visible UI
  change, table unchanged.")
- **Theme/components:** no new styling; if any alignment touches a Composable it continues to use the
  existing theme tokens (`ui/theme/`) and shared components (`ui/components/`) unchanged — the same
  "extend, don't duplicate" rule, here trivially satisfied by *not styling anything*.

---

## Out of Scope

- **Any architecture change or refactor.** No new pattern, no restructuring beyond the minimal
  behavior-preserving alignments in the checklist.
- **Dependency injection frameworks (Hilt/Koin).** The DI wiring shown in the lesson stays **manual**;
  migrating it is lesson **13d** (`docs/conceptos-pendientes.md` §5).
- **Any observable behavior change**, on any screen or background surface.
- **Backend, API contract (`docs/api/contract.md`), DB version, new dependency, new permission, new
  module** — none change.
- **New product UI or features.** No new screens, no new controls.
- **Broad test rewrites.** Only additive tests characterizing already-true behavior; no existing test is
  changed to fit a behavior change (there is none).

---

## Dependencies

- **Prerequisite lessons already shipped:** 02 (`ViewModel` + `StateFlow` + hoisted state), 04 (repos +
  pure domain functions), 07 (DataStore) — all ✅. The pattern to be named is fully present in the current
  `master`.
- **Existing anchor code must still exist** to be cited by the lesson: `TasksViewModel` (incl. the 04b
  `debounce`/`combine`/`stateIn` pipeline), `domain/tasks/ReminderPlanning.kt`, `urgencyLevelFor`,
  `deadlineProgressFor`, `TaskListShaping.kt`, `weeklyStatsFor`, `domain/sync/` reconcile, the
  `TaskRepository` interface, and the `ReminderSchedulingRepository` / `TaskSurfacesRefreshingRepository`
  decorators wired in `MainActivity`. Verify each is present before citing it.
- **Tracking files to update:** `tutorial/README.md` and `docs/conceptos-pendientes.md` §4.
- No external, tooling, or team dependencies.

---

## Risks

- **Scope creep into a refactor.** The biggest risk: a review that "names the pattern" turning into an
  architecture cleanup. **Mitigation:** the checklist gates every change to *minimal + behavior-preserving
  + justified*, and "no code change beyond the lesson" is an explicitly valid outcome (AC-8).
- **A silent behavior change hiding in an "obvious" alignment.** Extracting/moving code can subtly change
  timing or ordering. **Mitigation:** existing tests must pass **unchanged** (AC-2); extracted pure
  functions get an additive test; prefer *no change* when in doubt.
- **Diagram drift.** A diagram that describes an idealized architecture rather than the real one.
  **Mitigation:** label the diagram with the app's actual types/files and cross-check against the code
  cited in the lesson.
- **Teaching-value thinness.** As a mostly-documentation lesson, it risks feeling like filler.
  **Mitigation:** anchor every concept to real, already-written code the learner recognizes, and use the
  seam/decorator story (reminders + sync behind one interface) as the payoff that explains *why* the
  structure paid off — and sets up 13d.

---

## Approval

Please review and approve this spec before implementation begins. Approval covers **both** the plan of
work (a documentation-first consolidation lesson) **and** the constraint that any code change is minimal,
justified, and behavior-preserving — including the explicitly-allowed outcome of **no production code
change at all**. Once approved, work proceeds on `feature/architecture-consolidation`.
