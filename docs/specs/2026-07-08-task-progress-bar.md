# Feature 19 — Task-card time-elapsed progress bar (barra de progreso hacia el plazo)

- **Status:** Draft — awaiting approval. (Approval covers **both behaviour and look**; the *Visual & UX Design* section below is part of what is signed off.)
- **Date:** 2026-07-08
- **Type:** UI polish + tutorial feature (**no backend, no API contract change, no DB-version change, no new permission, no new dependency**)
- **Suggested branch:** `feature/task-progress-bar`
- **Planned tutorial lesson:** `tutorial/19-barra-progreso-tareas.md` (Spanish, numbered after the current latest lesson `tutorial/18-navegacion-accesibilidad.md`)
- **Maps to pending concepts:** `docs/conceptos-pendientes.md` §4 (arquitectura y Compose avanzado — animaciones) and §7 (recursos y UI / accesibilidad).
- **Mockup slice:** "Task-card time-elapsed progress bar" in [`docs/mockups/README.md`](../mockups/README.md) — the ⬜ row currently owned by "19 · barra de progreso *(planned)*", deferred by feature 17 ("v1: no progress bar"). This feature closes it.
- **Visual reference:** [`docs/mockups/rediseno-ux-ui.html`](../mockups/rediseno-ux-ui.html) — the `.bar` element under each task card's countdown (direction only — proportions/colors to interpret, **not** code to copy).

---

## Overview

Feature 17 shipped urgency as a **color-only** cue: the countdown text turns calm → soon → urgent/overdue
via `urgencyLevelFor` + `NeverLateExtras`, but it deliberately deferred the mockup's **progress bar**
("v1: no progress bar"). Feature 19 is the **direct continuation of feature 17**: it adds the deferred
determinate `LinearProgressIndicator` to each task card (`TaskRow`) so that, at a glance, the *fill* and
the *color* tell the same story — nearly empty and calm when there is plenty of time, filling and warming
as the deadline approaches, full and red when the time is up.

Its second purpose is didactic. It is the tutorial's first dedicated pass over **determinate progress
indicators** and adds a second, cleaner example of **`derivedStateOf` + a pure JVM-testable helper** (the
same split feature 17 used for `urgencyLevelFor` and feature 09 used for `ReminderPlanning.kt`). The
progress fraction is derived from the **same** live `remainingMillis` the countdown text and the urgency
color already read — there is exactly one source of truth for "how much time is left", and three views of
it (number, color, bar).

The surface area is deliberately small and low-risk: it extends the **existing** `TaskRow` composable and
adds one pure helper. No state shape, navigation, data, or contract changes.

---

## Goals

Success looks like:

- Each task card shows a **determinate progress bar** under the countdown whose fill reflects how much of
  the time toward the deadline has **elapsed**: near-empty at the start, near-full as the deadline nears,
  **full** when overdue.
- The bar is **colored by the same urgency level** as the countdown text (reusing feature 17's tokens), so
  color and fill are one coherent signal, never two competing ones.
- The fill **animates smoothly** when it changes, rather than snapping between values.
- The bar is **accessible**: its progress is announced to screen readers (it is a real indicator, not
  decoration), it never relies on color alone (the "Tiempo agotado" text and a textual percentage remain),
  and it stays legible at the largest system font scale.
- Everything is implemented by **extending** `TaskRow` and adding one pure helper — **no** change to
  `TaskUiModel`, `TasksUiState`, the ViewModels' `StateFlow`s, or the navigation graph.
- The tutorial gains `tutorial/19-barra-progreso-tareas.md` (Spanish), teaching the new concepts below.
- The mockup tracking row moves to ✅ in the Design review step.

Non-goals for "success": no new screen, no new stored data, no per-task progress persistence, and no
measurable performance target beyond "the once-a-second tick does not become more expensive" (the fraction
is derived, like the color, only when it actually changes meaningfully — see Risks).

---

## User Stories

### US-1 — Time-elapsed progress bar on each task card
**As a** user scanning my task list,
**I want** each task card to show a bar that fills as its deadline approaches,
**so that** I can gauge how much time is left at a glance, without reading the exact countdown.

**Acceptance criteria**
- Each `TaskRow` renders a **determinate** `LinearProgressIndicator` (a `progress` value in `0f..1f`),
  placed under the countdown, echoing the mockup's `.bar` element.
- The fill represents **elapsed fraction toward the deadline**: ~empty near the start of the window,
  ~full as the deadline approaches.
- An **overdue** task (`isTimedOut == true`) shows a **full** bar (`1f`).
- The fraction is **derived from the same** live `remainingMillis` / `isTimedOut` the countdown text and
  the urgency color already read — via `derivedStateOf`, **not** recomputed on a separate path or on every
  one-second tick that leaves the fraction visually unchanged.
- When a task has **no meaningful total window** to measure against (see Technical Approach — e.g. a
  deadline-only task with no duration and no start anchor), the row shows **no bar** rather than a
  misleading or arbitrary fill (the countdown text + color still convey urgency). *This "when does the bar
  appear" rule is the one product decision to confirm — see Risks.*

### US-2 — Color matches urgency (one coherent signal)
**As a** user,
**I want** the bar's color to match the countdown's urgency color,
**so that** fill and color reinforce each other instead of telling two different stories.

**Acceptance criteria**
- The bar's color is chosen from the **same** `UrgencyLevel` → color mapping the countdown text uses
  (`colorForUrgency` / `NeverLateExtras` calm/soon + Material `error` for urgent/overdue) — **no new
  colors are introduced**.
- Calm → calm color, Soon → soon color, Urgent/Overdue → Material `error` — identical to feature 17.
- The bar's **track** (unfilled portion) uses an existing themed low-emphasis token (e.g. the indicator's
  default track / a surface-variant tone), legible in both light and dark themes.
- Both the fill color and the fraction derive from the **same** `remainingMillis` value via
  `derivedStateOf`, so color and fill can never disagree.

### US-3 — Smooth animation on change
**As a** user watching a task get more urgent,
**I want** the bar to glide to its new fill,
**so that** the change reads as motion, not a jump.

**Acceptance criteria**
- The rendered fraction is animated with `animateFloatAsState` (ties to feature 17's animation lesson) —
  when the derived target fraction changes, the bar **eases** to it rather than snapping.
- The animation does not introduce a visible per-second "stutter": the target updates only when the
  derived fraction actually changes, and `animateFloatAsState` smooths between those updates.
- The transition to a **full** bar on becoming overdue is likewise animated (no instant jump to `1f`).

### US-4 — Accessible and legible
**As a** user relying on a screen reader, or using a very large font, or who does not perceive color,
**I want** the progress to be announced and readable without depending on color,
**so that** the bar is genuinely informative for me too.

**Acceptance criteria**
- The bar's progress is **announced to screen readers** — the determinate `LinearProgressIndicator`'s
  built-in progress semantics are preserved (or an explicit `Modifier.progressSemantics` / a
  `contentDescription`-style state description is provided). The bar is **not** marked purely decorative.
- The state is **never conveyed by color alone**: the existing "Tiempo agotado" / "Time's up" text for the
  overdue state remains, and a **textual percentage** (or equivalent remaining-time text, already present)
  accompanies the bar.
- The card layout **reflows without clipping or overlap at the largest system font scale**; the bar and
  its accompanying text stay legible (the bar is a fixed-height indicator, so it does not itself scale, but
  the surrounding text must not push it off-card or truncate).
- **Note:** the ≥48dp touch-target rule does **not** apply — this is a **non-interactive** indicator, not a
  control. Legibility at large font scale does apply; minimum touch size does not.

### US-5 — Tutorial lesson (mandatory per Tutorial Methodology)
**As a** learner following the tutorial,
**I want** a Spanish lesson explaining determinate progress indicators, the pure fraction helper, the
animation, and progress semantics,
**so that** the codebase stays a coherent progressive tutorial.

**Acceptance criteria**
- `tutorial/19-barra-progreso-tareas.md` exists, in Spanish, numbered after lesson 18.
- It teaches the concepts in "New concepts taught" below, walking through the code actually written, and
  references the prior lessons it builds on: **04** (task list, countdown, `CountdownTicker`) and **17**
  (urgency color via `urgencyLevelFor` + `derivedStateOf`), per methodology.

---

## Acceptance Criteria (behavioural, consolidated)

1. Every `TaskRow` with a meaningful time window renders a determinate `LinearProgressIndicator` with a
   `0f..1f` progress derived from the live `remainingMillis` / `isTimedOut`.
2. Fill grows as time elapses; an overdue task shows a full bar.
3. The fraction is computed by a **pure, JVM-testable** helper in `domain/tasks/` (no clock read of its
   own, no Android deps), consumed via `derivedStateOf` — not recomputed on every tick.
4. Bar color = the existing `UrgencyLevel` → color mapping; no new color values are added.
5. The rendered fill is animated via `animateFloatAsState`.
6. Progress is announced through the indicator's semantics; overdue and remaining state remain expressed in
   **text** as well as color.
7. A task with no meaningful window shows no bar (per the confirmed rule in US-1 / Risks) — never an
   arbitrary or misleading fill.
8. No change to `TaskUiModel`, `TasksUiState`, the ViewModels' public `StateFlow`s, or the nav graph.
9. Light and dark themes both remain legible; layout survives the largest font scale.

---

## Visual & UX Design

### Mockup slice
This feature implements the **"Task-card time-elapsed progress bar"** slice from
[`docs/mockups/README.md`](../mockups/README.md) — the ⬜ row deferred by feature 17. In the master mockup
[`rediseno-ux-ui.html`](../mockups/rediseno-ux-ui.html) it is the **`.bar`** element rendered under each
task card's countdown: a thin, fully-rounded track (`height: 6px; border-radius: 99px`) with an inner fill
whose width and color vary per card (the mockup illustrates fills tinted with its `--ok` / `--soon` /
`--late` roles — the same three urgency tiers feature 17 already mapped to `NeverLateExtras`/`error`).

The mockup's exact fill **widths and its track color are illustrative direction only** — translate the
*intent* (a thin, rounded, urgency-colored elapsed bar under the countdown) into Compose using the app's
**real** theme tokens (`ui/theme/` — `NeverLateExtras`, the type scale, Material 3 tokens and the
`LinearProgressIndicator` default track), **not** by copying its HTML/CSS or its literal percentages.

### Deferred visual polish
- **Brand-colored top app bars, leading-icon chips, branded FAB** remain owned by the planned **feature
  20 (cromo de marca)** — this feature does **not** touch card chrome beyond adding the bar. Those stay as
  their existing ⬜/🟡 rows in the tracking table.
- The bar's exact **corner rounding / thickness** should follow Material 3's `LinearProgressIndicator`
  defaults rather than pixel-matching the mockup's `6px`; if a rounded-cap style is not trivially
  available it is acceptable to ship the platform default and note any residual gap as a pending row
  (deferring is fine — deferring *silently* is not).

### Visual acceptance criteria (verified in the Design review step)
- **Color by urgency, reusing feature-17 tokens:** bar fill uses `colorForUrgency(urgencyLevel)` —
  `NeverLateExtras.colors.calm` / `.soon` and `MaterialTheme.colorScheme.error` for urgent/overdue. **No
  new color is defined.**
- **Overdue = full + error color:** a timed-out task shows a full bar in the `error` color.
- **Smooth animation on change:** the fill eases (via `animateFloatAsState`) when the derived fraction
  changes; it does not snap, and does not stutter on ordinary ticks.
- **Single source of truth:** the fraction and the color both derive from the **same**
  `remainingMillis`/`urgencyLevelFor` values via `derivedStateOf` — extend, don't duplicate; no second
  computation path.
- **Legible at the largest font scale:** the card reflows without clipping/overlap at max font scale; bar
  and text stay readable. (≥48dp touch target intentionally **not** required — non-interactive indicator.)
- **Progress announced via semantics:** the determinate indicator exposes its progress to accessibility
  services; the bar is not decorative-only.
- **Textual fallback present:** the "Tiempo agotado" text and a remaining-time / percentage text remain, so
  the state is never color-only.
- **Theme-safe:** fill and track both legible in light and dark.

---

## Technical Approach

High-level strategy — **extend `TaskRow`, add one pure helper.** All work is in the app UI + domain layers
(`app/`); nothing else moves.

### 1. Pure fraction helper (`domain/tasks/`)
- Add a pure, Android-free, JVM-testable helper — proposed `domain/tasks/DeadlineProgress.kt`, in the exact
  style of `Urgency.kt` / `ReminderPlanning.kt` (no clock read of its own; a test just passes values in).
  Proposed signature:
  `fun deadlineProgressFor(remainingMillis: Long, totalMillis: Long?, isTimedOut: Boolean): Float?`
  returning an **elapsed fraction clamped to `0f..1f`**, or **`null`** when there is no meaningful total
  (`totalMillis` null or ≤ 0). Overdue → `1f`.
- **Denominator (the elapsed window).** The live `remainingMillis` already exposed by `TaskUiModel` is the
  numerator's complement; the helper needs a **total window**. The natural, already-available total is the
  task's **`estimatedDurationMillis`** (elapsed = `total - remaining`, so `fraction = (total - remaining) /
  total`, clamped). Tasks without a usable total return `null` and simply show no bar (see US-1 AC and
  Risk 1). This keeps the helper pure and avoids inventing a start anchor the data model does not store
  (`Task` has no `createdAt`).
- Edge cases the helper (and its unit tests) must cover: `totalMillis == null` or `≤ 0` → `null`;
  `remaining >= total` → `0f`; `remaining <= 0` or `isTimedOut` → `1f`; normal case → clamped fraction.

### 2. `TaskRow` — derive + render + animate
- In `TaskRow`, alongside the **existing** `urgencyLevel` `derivedStateOf`, add a sibling
  `derivedStateOf { deadlineProgressFor(uiModel.remainingMillis, uiModel.task.estimatedDurationMillis, uiModel.isTimedOut) }`,
  keyed on the same inputs — so both the color and the fraction read the one live value and recompute only
  when it meaningfully changes.
- If the derived fraction is non-null, animate it with `animateFloatAsState(targetValue = fraction)` and
  render a determinate `LinearProgressIndicator(progress = { animated }, color = colorForUrgency(urgencyLevel), …)`
  under the countdown row; if null, render nothing (no bar).
- Apply progress semantics: rely on the determinate indicator's built-in `progressSemantics`, and/or add a
  state description so screen readers announce it; keep the existing overdue text and remaining-time text
  as the color-independent fallback (optionally add a short textual percentage).

### 3. Strings & theme
- Any new copy (e.g. an accessibility state description / percentage label) goes in **both**
  `res/values/strings.xml` (Spanish base) and `res/values-en/strings.xml` (feature 08). Use `<plurals>` /
  locale-aware `NumberFormat` if a number is shown, per existing conventions.
- **No new colors** — reuse `colorForUrgency` and the indicator's default track.

### Files in scope
- **New:** `app/src/main/java/com/neverlate/domain/tasks/DeadlineProgress.kt` — the pure fraction helper.
- **New:** `app/src/test/java/.../domain/tasks/DeadlineProgressTest.kt` — JVM unit tests for the helper.
- `app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt` — extend `TaskRow` (second `derivedStateOf`,
  `animateFloatAsState`, the `LinearProgressIndicator`); reuse the existing `colorForUrgency`.
- `app/src/main/res/values/strings.xml` + `app/src/main/res/values-en/strings.xml` — any new a11y/percent
  copy.
- **New:** `tutorial/19-barra-progreso-tareas.md` (Spanish lesson).
- **Design review:** update `docs/mockups/README.md` (move the slice row to ✅).

---

## New concepts taught (tutorial mandate)

To be developed in `tutorial/19-barra-progreso-tareas.md` (Spanish); listed here so the spec captures the
didactic intent, per the Tutorial Methodology in `CLAUDE.md`:

- **Determinate vs indeterminate progress indicators.** Why a determinate `LinearProgressIndicator` takes a
  `0f..1f` value, when you use determinate vs indeterminate, and how "elapsed fraction" maps onto that
  range.
- **Deriving progress from a pure, JVM-testable helper.** Building `deadlineProgressFor` in `domain/tasks/`
  in the same style as `urgencyLevelFor` and `ReminderPlanning.kt` — a pure function with no clock, its
  edge cases (no total, already overdue, deadline still far off), and why that makes it trivially unit
  testable.
- **Animating a value with `animateFloatAsState`.** Smoothing the bar's fill between target changes; the
  connection to feature 17's animation lesson, and why you animate the *rendered* value while the *target*
  comes from `derivedStateOf`.
- **Progress semantics & accessibility.** `Modifier.progressSemantics` / the semantics a determinate
  `LinearProgressIndicator` provides for free, why a visual indicator must **announce** itself to screen
  readers, and why the state must not depend on color alone (textual fallback).

---

## Out of Scope

- Any **backend, API contract, DB schema/version, permission, module, or dependency** change — all APIs
  used (`LinearProgressIndicator`, `animateFloatAsState`, `derivedStateOf`, `progressSemantics`) are in the
  Compose BOM already on the classpath.
- Changing state shapes: `TaskUiModel`, `TasksUiState`, or the ViewModels' public `StateFlow`s (in
  particular, **no** new stored `createdAt`/start-anchor field — the bar uses the existing
  `estimatedDurationMillis` window; if a start-anchored deadline bar is wanted later, that is a separate
  feature).
- Brand-colored app bars, leading-icon chips, or branded FAB styling (owned by planned feature 20).
- A progress bar on any surface **other** than the task card (no widget/notification/Articles progress).
- Pixel-matching the mockup's exact bar thickness/rounding if it fights Material 3 defaults (default is
  acceptable; note any residual gap as pending).
- Gesture/interaction on the bar — it is a read-only indicator, never a slider/seek control.
- Respecting the system "reduce motion" setting for the fill animation (noted as a Risk, not committed).

---

## Dependencies

- **Feature 17 (states & animations)** — this feature is its direct continuation: it reuses
  `urgencyLevelFor`, the `UrgencyLevel` enum, `colorForUrgency`, and the `derivedStateOf` pattern in
  `TaskRow`. Those must remain as they are.
- **Feature 04 (task list, countdown, `CountdownTicker`)** — the live `remainingMillis` pipeline feeding
  `TaskUiModel` is the single source the fraction derives from.
- **Feature 16 (visual identity)** — `NeverLateExtras` calm/soon colors, reused (not extended) for the bar.
- **Feature 08 (i18n)** — any new copy needs Spanish + English string resources.
- **Compose animation/progress APIs** — already available via the existing Compose BOM; **confirm no
  version-catalog change is needed** (expected: none).
- **Resolution of Risk 1 (the "when does the bar appear" rule)** before implementation begins.

---

## Risks / Open Questions

1. **Progress denominator / when the bar appears (main open question).** A "% elapsed toward the deadline"
   bar needs a well-defined *total window*. The data model stores `estimatedDurationMillis`, `deadline`,
   and a live-timer state, but **no `createdAt`/start anchor**, so a deadline-only task with no duration has
   no natural fraction. **Proposed decision (to confirm):** base the fraction on `estimatedDurationMillis`
   (elapsed = total − remaining); tasks without a usable total show **no bar** (color + countdown still
   convey urgency). Alternative if the user prefers the bar to always show: introduce a stored start anchor
   — but that changes the DB and is explicitly out of scope here.
2. **Animation vs the once-a-second tick.** The target fraction must update from the live `remainingMillis`
   without making the tick more expensive or causing a visible stutter. Mitigation: target comes from
   `derivedStateOf` (recomputes only on meaningful change); `animateFloatAsState` smooths between targets.
   QA should confirm no jank and no per-second flicker.
3. **Accessibility correctness.** The indicator must actually announce its progress (not be swallowed as
   decorative), and the color-independent textual fallback must remain. QA should verify with TalkBack that
   progress is announced and that overdue is legible without color.
4. **Large font scale layout.** Adding a bar under an already-dense card must not clip or overlap at max
   font scale. QA/Design review should check the largest scale in both themes.
5. **Rounded-cap styling gap.** The mockup's fully-rounded thin bar may not match Material 3's default
   `LinearProgressIndicator` exactly. Shipping the default and recording any residual gap as a pending row
   is acceptable; pixel-matching is not required.

---

## Review

Please review this specification and confirm — approval covers **both behaviour and the Visual & UX Design
section**:

- **Risk 1 — the denominator / "when the bar appears" rule:** base the fraction on
  `estimatedDurationMillis` and show **no bar** when there is no usable total (recommended), vs. some other
  window. This is the one product decision needed before implementation.
- The **color mapping is reused, not extended** (no new colors) — confirm that is desired.
- That shipping Material 3's **default bar styling** (rather than pixel-matching the mockup's rounding) is
  acceptable, with any residual gap tracked as pending.
- The **non-interactive** treatment (no ≥48dp target; legibility-at-large-font-scale instead) is the right
  accessibility bar for an indicator.

Once approved, implementation proceeds on `feature/task-progress-bar` per the New Feature Workflow in
`CLAUDE.md` (spec → approval → branch → `mobile-engineer` implementation → `qa-engineer` tests → Design
review + mockup-tracking update → Spanish lesson `tutorial/19-barra-progreso-tareas.md` → commit).
