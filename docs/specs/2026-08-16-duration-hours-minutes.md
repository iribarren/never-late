# Feature — Duración de la tarea en horas y minutos

**Status:** Draft — awaiting approval
**Suggested branch:** `feature/duration-hours-minutes`
**Tutorial:** `No (solo producto)` — rediseño de un formulario existente: state hoisting,
`OutlinedTextField` y la validación pura ya se enseñaron en la lección 04, no hay concepto nuevo que
enseñar.
**Type:** Presentation-only change to the task edit form — **no** backend, **no** API contract,
**no** Room schema/migration, **no** new dependency, **no** mockup slice.

---

## Overview

Today the task edit form (`TaskEditScreen`) asks for the estimated duration through a single field,
**"Duración estimada (minutos)"**, that takes a whole number of minutes. A person who wants "2 h 30
min" has to do the arithmetic in their head and type `150` — a small but real friction, and exactly
the kind of mental math this app exists to remove for people with ADD/ADHD.

This feature replaces that single field with **two contiguous numeric fields side by side — hours
and minutes** — so a duration is entered the way people actually think about it. When editing an
existing task, both fields are **pre-filled** from the stored value (90 min → `1` h and `30` min).

Crucially this is a **presentation-only change**. The Room column `estimatedDurationMillis` keeps
storing **milliseconds** exactly as before. The two fields are combined into the same
`estimatedDurationMillis` at save time, and split back into (hours, minutes) at load time. Nothing
downstream — the task list, the widget, the notification, the progress bar, the `TaskDto` wire shape
— changes at all: they keep reading the identical millis they always have. There is **no migration,
no schema change, and no contract change**.

The rule for "what counts as a valid duration" stays a **single pure function** in
`TaskValidation.kt`, JVM-tested, now taking two inputs (hours text + minutes text) instead of one.
No parsing logic leaks into the ViewModel or the composable.

## Goals

- A person can enter an estimated duration as hours **and** minutes without doing arithmetic in
  their head — "2 h 30 min" no longer forces typing `150`.
- Editing a task pre-fills both fields from the stored duration, correctly split (90 min → 1 h /
  30 min).
- The stored value, and everything that reads it (list, widget, notification, progress bar,
  `TaskDto`), is **byte-for-byte unchanged** — this is a UI-input change only.
- Validation remains one pure, unit-tested function; every edge case (minutes ≥ 60, both empty, both
  zero, non-numeric, negative/overflow) has a defined, tested behaviour.
- Existing helpers are reused, not duplicated: `durationParts` splits millis for the pre-fill;
  `validateTaskForm` is extended to two inputs.

## User Stories

### US-1 — Entering a duration as hours and minutes
*As someone creating a task, I want to enter the estimated duration as hours and minutes in two
separate fields, so that I don't have to convert "2 h 30 min" into 150 minutes in my head.*

**Acceptance criteria**
- The single "Duración estimada (minutos)" field is replaced by **two numeric fields side by side**:
  hours (left) and minutes (right), each with its own visible label.
- Both fields use the numeric keyboard (`KeyboardType.Number`), as the current single field does.
- Typing `2` in hours and `30` in minutes and saving stores a duration of `2 h 30 min`
  (= 9 000 000 ms) in `estimatedDurationMillis`.
- The rest of the form (title, deadline picker, priority chips, save/delete buttons) is unchanged.

### US-2 — Editing pre-fills both fields
*As someone editing an existing task, I want the hours and minutes fields to already show the task's
current duration, so that I can adjust it instead of re-entering it.*

**Acceptance criteria**
- Opening a task with a stored duration of 90 min shows `1` in hours and `30` in minutes.
- Opening a task with a stored duration of 45 min shows `0` (or empty — see Visual & UX) in hours
  and `45` in minutes.
- Opening a task with **no** stored duration (duration-only-optional task with a deadline) shows both
  fields empty.
- The split reuses the existing `durationParts(millis)` helper in `TaskTiming.kt` — the ViewModel
  does **not** compute `it / 60_000L` by hand anymore.
- Saving an edit without touching the duration fields round-trips the **same** millis value it loaded
  (no drift from split-then-recombine).

### US-3 — Duration stays optional
*As someone who only cares about a deadline, I want to leave the duration blank, so that I'm not
forced to invent an estimate.*

**Acceptance criteria**
- Both duration fields empty **and** a deadline set → the form is **valid** and saves with
  `estimatedDurationMillis = null` (unchanged from today's behaviour).
- Both duration fields empty **and** no deadline → **invalid**, showing the existing
  "indica una duración o una fecha límite" message (`MISSING_DURATION_OR_DEADLINE`, unchanged).

### US-4 — Invalid duration input is rejected with a clear message
*As someone who mistypes or pastes junk into a duration field, I want a clear error instead of a
crash or a silently-wrong save, so that I can correct it.*

**Acceptance criteria**
- Every edge case below resolves to the defined outcome (see **Edge case matrix**), and an invalid
  duration shows the rewritten `tasks_error_invalid_duration` message.
- No input can crash the form or persist a nonsensical duration (negative, overflowed, or
  non-numeric).

## Edge case matrix (normative — the implementer follows this table exactly)

The **total** duration is `hours * 60 + minutes` minutes. "Empty" means the trimmed field is the
empty string.

| Hours field | Minutes field | Total | Outcome | Stored value |
|---|---|---|---|---|
| empty | empty | — | **Valid** *iff a deadline is set* (duration optional, US-3) | `null` |
| empty | `30` | 30 min | Valid | 30 min |
| `2` | empty | 120 min | Valid (hours + 0 min) | 120 min |
| `2` | `30` | 150 min | Valid | 150 min |
| `0` | `90` | 90 min | **Valid — accept and normalize** (total is what matters; 0 h 90 min = 90 min) | 90 min |
| `0` | `0` | 0 min | **Invalid** — a zero total is not a valid explicit duration | — |
| empty | `0` | 0 min | **Invalid** — zero total | — |
| `0` | empty | 0 min | **Invalid** — zero total | — |
| `-1` | anything | — | **Invalid** — negatives rejected | — |
| `abc` / pasted junk | — | — | **Invalid** — non-numeric rejected (see note) | — |
| huge value that overflows `Long` millis | — | — | **Invalid** — parse/overflow rejected | — |

**Decisions locked by this table (do not re-litigate in implementation):**

- **Minutes ≥ 60 is accepted and normalized**, not rejected. `0 h 90 min` is a valid 90-minute
  duration. Rejecting it would be user-hostile — the only thing that matters is the total, and the
  minutes field being "over 60" is a presentation artifact, not an error. The stored millis is simply
  `total_minutes * 60_000`.
- **Both zero (or a zero total) is invalid.** This matches today's rule: `parseDurationMinutes`
  currently requires `> 0`. An explicit duration of zero is meaningless; if the person wants no
  duration they leave both fields empty (which is valid when there's a deadline).
- **Non-numeric input reaches validation as-is and must be rejected.** `KeyboardType.Number` only
  chooses the on-screen keyboard; it does **not** filter pasted text, hardware-keyboard input, or IME
  quirks. A pasted `"abc"` or `"1.5"` therefore arrives at `validateTaskForm` unchanged and must fail
  validation (the current single field already relies on this — `toLongOrNull()` returns null).
- **Negatives and overflow are rejected by parsing.** Each field is parsed with `toLongOrNull()`
  (which already rejects non-integers, and returns null on overflow past `Long`), and the combined
  total is required to be `> 0`. A leading `-` makes a field parse to a negative or fail the `> 0`
  check, so `-1` is rejected either way. The recombination `total * 60_000L` must not silently
  overflow; a total large enough to overflow the millis `Long` is treated as invalid.

## Acceptance Criteria (consolidated)

### Behavioural
- The task form has two duration fields (hours, minutes); creating and editing both work per US-1/US-2.
- The full **Edge case matrix** above holds, proven by JVM unit tests on the extended validation
  function (one test per row).
- The pre-fill uses `durationParts`; the recombine produces the identical millis for an untouched
  edit (round-trip test).
- The stored `estimatedDurationMillis` for a given (hours, minutes) equals `(hours*60+minutes)*60_000`
  and is read unchanged by the list/widget/notification/progress-bar/`TaskDto` (no code in those paths
  is touched).
- The validation rule lives **only** in `TaskValidation.kt`; grep confirms no `/ 60_000` or
  `* 60_000` duration math in `TaskEditViewModel` or `TaskEditScreen` beyond delegating to the helpers.

### Visual (verified in the Design review step)
- Hours and minutes render as two fields in a `Row`, each sharing the width via `weight`, each with
  its own visible label (no single shared label, no unit concatenated in Kotlin).
- Both fields keep a touch target ≥ 48 dp.
- At the **largest system font scale** the two-field row reflows without clipped labels, overlap, or
  **horizontal overflow** (see Visual & UX for the narrow-screen behaviour).
- The validation-error text still appears below the fields in `colorScheme.error`, as today.
- The form still uses the branded top app bar (`brandedTopAppBarColors()`) and existing theme
  tokens — no one-off styling introduced.

### Definition-of-Done items this feature touches
- **Tests pass.** Pure validation logic has JVM unit tests covering every row of the edge-case
  matrix plus the pre-fill/round-trip. `./gradlew :app:testDebugUnitTest` green before commit.
- **i18n holds.** New/changed strings exist in **both** `values/` (Spanish base) and `values-en/`,
  kept in sync; no unit words concatenated in Kotlin.
- **Every state is designed.** The form's validation-error state is covered (reused, not reinvented).
- **No migration / no contract / no schema change** — explicitly asserted; `docs/api/contract.md`
  and `app/schemas/` are untouched, and that is correct, not a missing item.
- **Docs match reality.** `docs/mockups/README.md` gains **no** row (this claims no slice — see
  Visual & UX); no other doc changes are required by this diff.

## Visual & UX Design

### Master mockup slice — **none claimed**

`docs/mockups/rediseno-ux-ui.html` is the app's visual north star, but it has **no row for the task
edit form / duration input** in `docs/mockups/README.md`. This feature is therefore **net-new UI on
an existing screen that claims no mockup slice** — and **no tracking-table row is added or claimed**.
The mockup may be consulted as general *direction* (spacing, field treatment), but its intent is
translated with the app's real theme tokens (`ui/theme/` — the Material 3 type scale, `NeverLateExtras`),
never copied from its HTML/CSS. Nothing visual is silently deferred: there is simply no slice here.

### Layout of the two fields

- The two `OutlinedTextField`s (hours, minutes) sit in a **`Row`** where each field carries
  `Modifier.weight(1f)`, so they split the available width evenly, with a small horizontal gap
  (`Arrangement.spacedBy(8.dp)`, matching the form's existing 8 dp rhythm).
- Each field has its **own label**: hours = "Horas" / "Hours", minutes = "Minutos" / "Minutes". The
  units live in the labels (string resources), never concatenated onto the value in Kotlin.
- The row occupies the same vertical slot the single duration field occupies today (below the title
  field, above the deadline field), keeping `padding(top = 8.dp)`.

### Reflow and font scaling

- Because each field is `weight(1f)` inside a `Row`, the two fields **always share the row width**
  and never push past the screen edge — there is no fixed width to overflow. On a narrow screen the
  fields simply get narrower; the numeric content (at most a few digits) stays legible.
- At the **largest font scale**, the labels grow; `OutlinedTextField` wraps/ellipsizes its label
  within its own box rather than forcing the row wider, so the **row must not produce horizontal
  overflow**. This is a visual AC to verify. (If a label proves too long to read at max scale, the
  labels are short by design — "Horas"/"Minutos" — precisely to survive scaling.)
- The whole form already lives in a `verticalScroll` column, so added height from wrapped labels
  scrolls rather than clips.

### Theme & component reuse

- Reuse `OutlinedTextField` with the same styling the existing duration/title fields use — no custom
  field component.
- Keep the branded top app bar (`brandedTopAppBarColors()`) and the existing error-text treatment
  (`colorScheme.error`). No new colors, shapes, or spacing tokens are introduced.

## Technical Approach

High level: change **only** how (hours, minutes) are entered and split; keep the domain value in
milliseconds and keep the validation rule pure and single-homed. Sub-project: **`app/`** only.

- **Validation (`data/tasks/TaskValidation.kt`).** Extend `validateTaskForm` to take the duration as
  **two** parameters (e.g. `durationHoursText: String, durationMinutesText: String`) instead of the
  single `durationMinutesText`. The private parser is generalized (e.g. `parseDuration(hoursText,
  minutesText): Long?`) to: treat each empty field as `0`, parse each with `toLongOrNull()`
  (rejecting non-numeric/negatives), require the **combined total minutes** to be `> 0` when *any*
  field is non-empty, guard the `* 60_000L` against overflow, and return `null` for any invalid
  combination — while still returning `null` **duration** (valid, optional) when **both** fields are
  empty. All the "what is a valid duration" logic stays in this one file; `TaskValidationError.INVALID_DURATION`
  and `MISSING_DURATION_OR_DEADLINE` are reused unchanged.
- **ViewModel (`ui/tasks/TaskEditViewModel.kt`).** In `TaskEditUiState`, replace
  `estimatedDurationMinutes: String` with two fields (`durationHours: String`, `durationMinutes:
  String`). In `init`'s pre-fill, replace the hand-rolled `it / 60_000L` with **`durationParts(it)`**
  from `TaskTiming.kt`, mapping the (hours, minutes) pair into the two string fields (0 rendered as
  empty or "0" per the Visual choice — pick one and test it). Replace `onDurationMinutesChange` with
  two handlers (`onDurationHoursChange`, `onDurationMinutesChange`), each clearing `validationError`
  as the existing one does. `save()` passes both strings to the extended `validateTaskForm`; the rest
  of `save()` (building the `Task`, keeping timer fields) is unchanged.
- **Screen (`ui/tasks/TaskEditScreen.kt`).** Replace the single duration `OutlinedTextField` with a
  `Row` of two `weight(1f)` fields wired to the two new callbacks and two new labels. Update
  `TaskEditRoute` to pass the two callbacks through. Update the two `@Preview`s to seed the new state
  fields.
- **Strings.** See i18n section.
- **No other files.** The list, widget, notification, progress bar, `TaskDto`, Room entity/DAO, and
  the `docs/api/contract.md` are **not** touched — asserted and checked by grep in the Design/QA step.

### i18n (both `values/` and `values-en/`, kept in sync)

- **New:** `task_edit_duration_hours_label` = "Horas" / "Hours"; `task_edit_duration_minutes_label`
  = "Minutos" / "Minutes".
- **Changed:** `task_edit_duration_label` currently reads "Duración estimada (minutos)" /
  "Estimated duration (minutes)". It stops mentioning minutes. Either repurpose it as a section
  heading above the row ("Duración estimada" / "Estimated duration") or retire it if the two field
  labels are self-explanatory — the spec's recommendation is to **keep it as a short heading**
  ("Duración estimada" / "Estimated duration") so the pair of fields reads as one labelled group.
- **Changed:** `tasks_error_invalid_duration` is rewritten to reflect two-field entry, e.g.
  "Introduce horas y minutos válidos (la duración total debe ser mayor que 0)." /
  "Enter valid hours and minutes (the total duration must be greater than 0)."
- **Unchanged:** `tasks_error_missing_duration_or_deadline` and all other task-edit strings.
- No unit words are concatenated in Kotlin; every user-facing string is a resource.

## Out of Scope

- **Any backend, API contract, or `TaskDto` change** — the wire shape and `estimatedDurationMillis`
  semantics are untouched.
- **Any Room schema change or migration** — the column, its type, and `NeverLateDatabase` version
  stay exactly as they are. (There is deliberately nothing to migrate.)
- **Any new dependency** — built entirely from existing Compose/Material 3 primitives and existing
  helpers.
- **Any mockup slice / tracking-table row** — see Visual & UX; nothing is claimed or deferred.
- **Days, weeks, or a duration *picker* dialog** — this feature is two plain numeric fields, not a
  wheel/segmented picker. A richer picker, if ever wanted, is separate future work.
- **Changing how the duration is *displayed* elsewhere** (task row "1 h 30 min" label, progress bar,
  notification) — those already render from millis via `durationParts` and are not touched.
- **Localizing number input beyond digits** (grouping separators, non-ASCII digits) — out of scope;
  plain integer parsing as today.
- **A Spanish tutorial lesson** — `Tutorial: No (solo producto)`; no `tutorial/NN-*.md`, no number
  reserved, no `docs/conceptos-pendientes.md` / `tutorial/README.md` change.

## Dependencies

- **No new libraries, permissions, or backend work.** Everything needed already exists.
- **Reused helpers (must be reused, not re-implemented):**
  - `durationParts(millis): Pair<Long, Long>` in
    `app/src/main/java/com/neverlate/data/tasks/TaskTiming.kt` — splits stored millis into (hours,
    minutes) for the edit pre-fill.
  - `validateTaskForm` + its private duration parser in
    `app/src/main/java/com/neverlate/data/tasks/TaskValidation.kt` — extended to two inputs; remains
    the single home of duration validity.
- **Files expected to change (implementation note):**
  - `app/src/main/java/com/neverlate/ui/tasks/TaskEditScreen.kt` — the two-field row + previews.
  - `app/src/main/java/com/neverlate/ui/tasks/TaskEditViewModel.kt` — `TaskEditUiState`, pre-fill via
    `durationParts`, the two change handlers, and `save()` wiring.
  - `app/src/main/java/com/neverlate/data/tasks/TaskValidation.kt` — extended validation/parse.
  - `app/src/main/res/values/strings.xml` and `app/src/main/res/values-en/strings.xml` — new/updated
    labels and the rewritten error string.
  - Existing validation unit tests for `validateTaskForm` (their call sites change to the two-arg
    signature) plus new tests for the edge-case matrix.

## Risks

- **Silent behaviour change from the "minutes ≥ 60 accepted" decision.** Someone reviewing might
  expect 60+ minutes to be rejected. *Mitigation:* the decision is locked in the Edge case matrix,
  justified (total is what matters), and covered by an explicit unit test (`0 h 90 min → 90 min`).
- **Round-trip drift.** Splitting millis → (h, m) → strings → back to millis could drift if the split
  and recombine disagree. *Mitigation:* both sides go through the same minute granularity
  (`durationParts` divides by 60_000; recombine multiplies total minutes by 60_000), and a
  load-then-save-untouched round-trip test guards it. Note durations are stored at minute
  granularity already, so no sub-minute precision exists to lose.
- **Pasted / non-numeric input.** `KeyboardType.Number` does not filter pasted text, so junk reaches
  validation. *Mitigation:* validation rejects it (the current single field already depends on this);
  covered by a non-numeric test row. Optionally the change handlers could filter to digits, but the
  authoritative guard stays in the pure validator, not the UI.
- **Overflow on recombine.** A pathologically large hours value could overflow `total * 60_000L`.
  *Mitigation:* parse guards and an overflow-rejection test row; treat overflow as invalid rather
  than wrapping.
- **Test call-site churn.** Existing `validateTaskForm` tests use the one-arg duration signature and
  will not compile until updated. *Mitigation:* update them in the same branch as part of the QA
  step; this is expected, not a surprise.
- **Very low overall risk:** no persistence, wire, or downstream-consumer code is touched, so the
  blast radius is confined to the edit form and its pure validator.

---

## Approval

Please review this specification. Approval covers **behaviour, look, and the tutorial decision**
(`No (solo producto)`) — the Edge case matrix (especially "minutes ≥ 60 accepted and normalized" and
"zero total invalid") and the Visual & UX Design section are part of what is signed off.
Implementation will not begin until you explicitly approve.
