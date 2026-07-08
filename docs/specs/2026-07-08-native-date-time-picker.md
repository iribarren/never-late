# Feature 14 — Native Material 3 date/time picker for the task deadline

- **Status:** Draft — awaiting approval
- **Date:** 2026-07-08
- **Suggested branch:** `feature/date-picker`
- **Scope:** Android app only (`app/`) — `TaskEditScreen.kt` and `TaskEditViewModel.kt`
- **Visual reference:** `docs/mockups/rediseno-ux-ui.html` (the "La fricción #1 · introducir la fecha límite" before→after block and the picker panel — direction only, not code)

---

## Overview

Today the task `deadline` is entered as **free text** in a fixed `dd/MM/yyyy HH:mm` pattern
(`TaskEditScreen.kt`, the third `OutlinedTextField`). This is the app's single biggest input
friction point: users must know the exact pattern, type separators by hand, and a typo yields the
`INVALID_DEADLINE_FORMAT` validation error as a routine outcome rather than a rare one.

Feature 14 replaces that free-text field with a **native picker flow**. The deadline field becomes a
`readOnly` `OutlinedTextField` with a **calendar `trailingIcon`**. Tapping the field or the icon
opens a Material 3 `DatePickerDialog`; confirming the date then opens a `TimePicker`; confirming the
time writes the chosen instant back into the form. The field still **shows** the chosen deadline,
formatted **locale-aware** (via the existing `formatDeadlineForDisplay`), but the user never types
the pattern again — so `INVALID_DEADLINE_FORMAT` disappears as a normal path.

The rest of the form (title, duration) and all business validation (future-date check,
duration-or-deadline requirement) are **unchanged**. There is **no backend, API contract,
DB-version, permission, or dependency change** — Material 3's `DatePicker`/`TimePicker` ship with the
Compose Material 3 library already on the classpath.

This is also a **tutorial feature** (see Tutorial Methodology in `CLAUDE.md`): lesson
`tutorial/14-selector-fecha.md` (Spanish, numbered after lesson 13) is a required deliverable.

## Goals

- Replace free-text deadline entry with a guided, tap-driven **native date + time picker**.
- Eliminate `INVALID_DEADLINE_FORMAT` as a routine user-facing error.
- Keep the displayed deadline **locale-aware** and legible.
- **Reuse** the existing `TaskTiming.kt` formatting/parsing helpers and the existing
  `TaskEditViewModel` seam — introduce **no new date logic**.
- Correctly handle the Material 3 `DatePickerState` **UTC-midnight** millis so a chosen date never
  lands "one day earlier".
- Teach new Compose/Material 3 concepts through the change (dialog UI state, `rememberDatePickerState`
  / `rememberTimePickerState`, the UTC→local pitfall, `readOnly` + accessible `trailingIcon`).

## User Stories

### US-1 — Pick a deadline instead of typing it
**As a** person with ADHD using the task form,
**I want to** pick the deadline date and time from native calendar/clock dialogs,
**so that** I never have to remember or type a date pattern.

**Acceptance Criteria**
- The deadline field is `readOnly` (no soft keyboard opens on focus) and shows a calendar
  `trailingIcon`.
- Tapping the field or the icon opens a Material 3 `DatePickerDialog`.
- Confirming the date opens a time picker (`TimePicker`) to choose hour and minute.
- Confirming the time closes the flow and writes the chosen deadline into the form; the field then
  displays it.
- Dismissing/cancelling either dialog leaves the field's previous value unchanged.

### US-2 — See the chosen deadline formatted for my locale
**As a** user,
**I want** the selected deadline shown in my device's date/time conventions,
**so that** it reads naturally (day/month order, 12h/24h) regardless of language.

**Acceptance Criteria**
- The field text is produced by the existing `formatDeadlineForDisplay(epochMillis, locale)` using
  the current configuration locale.
- No hardcoded `dd/MM/yyyy HH:mm` string is shown to the user for a picked value.

### US-3 — Edit an existing task's deadline
**As a** user editing a task that already has a deadline,
**I want** the picker to open on the current deadline and the field to show it,
**so that** editing is a small adjustment, not re-entry.

**Acceptance Criteria**
- When the form loads a task with a deadline, the field displays that deadline (locale-aware) and the
  date/time pickers open pre-set to it.
- A task with no deadline shows an empty field and the picker opens on a sensible default (e.g.
  today / current time).

### US-4 — The chosen day is the day that gets saved (timezone correctness)
**As a** user in any timezone,
**I want** the date I tap to be the date that is stored,
**so that** a deadline never shifts to the previous day.

**Acceptance Criteria**
- The `DatePickerState.selectedDateMillis` (UTC midnight) is interpreted as a **calendar date**, then
  combined with the picked hour/minute and resolved in the **device's local zone**
  (`ZoneId.systemDefault()`) before being turned into the epoch-millis deadline.
- For a user behind UTC (negative offset), picking a date does **not** produce the previous day's
  deadline. This is covered by a JVM unit test with a fixed non-UTC zone.

### US-5 — Business validation still applies
**As a** user,
**I want** the existing rules (deadline must be in the future; a task needs a duration or a deadline)
to keep protecting me,
**so that** the switch to a picker doesn't weaken correctness.

**Acceptance Criteria**
- Saving still runs `validateTaskForm(...)`; the future-date and duration-or-deadline errors still
  surface as before.
- `INVALID_DEADLINE_FORMAT` is no longer reachable through normal picker use (a picked value always
  parses). The error enum member and its string may remain for the parse helper's contract but should
  not appear via the UI in normal operation.

## Technical Approach

High-level only — no code here.

### Where it changes
- **`TaskEditScreen.kt`** — the stateless composable gains the picker UI.
- **`TaskEditViewModel.kt`** — unchanged in **shape**. `deadlineText` stays a `String`, and the
  ViewModel keeps receiving a deadline string through the existing `onDeadlineChange` callback.

### Reuse contract (no new date logic)
- Parsing/formatting must go **only** through the existing `data/tasks/TaskTiming.kt` helpers:
  - `formatDeadlineForDisplay(epochMillis, locale)` — what the field **shows**.
  - `formatDeadlineForInput(epochMillis)` — produces the canonical `dd/MM/yyyy HH:mm` string.
  - `parseDeadline(text)` — the inverse, used by validation.
- **Seam preservation strategy:** the picker computes a chosen epoch-millis, converts it with
  `formatDeadlineForInput(...)` into the canonical string, and passes **that string** to the existing
  `onDeadlineChange`. The ViewModel and `validateTaskForm` continue to consume `deadlineText`
  exactly as today. This keeps the ViewModel and validation untouched while the UI drives a picker.
  (If a display/canonical split is preferred later, it can be added additively — but v1 keeps the
  single `deadlineText` field to honour the "seam does not change shape" constraint.)

### Dialog state lives in the UI, not the ViewModel
- Dialog open/closed flags and the transient `DatePickerState` / `TimePickerState` are **ephemeral UI
  state**, held via `remember` in the composable — they are not screen data that must survive process
  death, and they don't belong in `TaskEditUiState`. This mirrors the project's state-hoisting
  convention: durable form values live in the ViewModel; momentary UI affordances live in the
  composable.

### The UTC→local conversion (the key correctness point)
- `rememberDatePickerState().selectedDateMillis` is **UTC midnight** of the picked day. It must be
  read as a **LocalDate** (via UTC), then combined with the `TimePicker`'s hour/minute into a
  `LocalDateTime`, and resolved through `ZoneId.systemDefault()` to epoch millis. Feeding the raw UTC
  millis straight into a local-zone formatter is what causes the "one day earlier" bug. This
  conversion is the single most test-worthy line of the feature.

### Accessibility
- The `trailingIcon` (calendar) must have a `contentDescription` (a new string resource), so
  TalkBack announces its purpose. The `readOnly` field must still be tappable to open the flow.

### Strings
- New string resources (Spanish base in `res/values/strings.xml`, English in `res/values-en/`):
  the calendar-icon `contentDescription`, and any dialog confirm/dismiss labels not covered by the
  Material 3 defaults. Follow the existing `task_edit_*` naming.

## Out of Scope

- Any backend, `docs/api/contract.md`, DB schema/version, permission, or dependency change.
- Changes to the title or duration fields, or to `validateTaskForm` rules/semantics.
- Recurring deadlines, relative/"quick" presets (e.g. "tonight", "in 2 hours"), or natural-language
  date entry.
- Timezone selection UI — the device's local zone is always used.
- Removing the `INVALID_DEADLINE_FORMAT` enum member or `parseDeadline` (they remain the parsing
  contract; only the free-text *entry path* goes away).
- Redesigning the rest of the task form or the broader UX refresh implied by the mockup file.
- A separate "clear deadline" affordance is **optional**; if trivial it may be included, otherwise it
  is out of scope for v1 (call it out during implementation).

## Dependencies

- **Material 3 Compose** (`androidx.compose.material3`) already on the classpath — provides
  `DatePicker`, `DatePickerDialog`, `rememberDatePickerState`, `TimePicker`, `rememberTimePickerState`.
  These APIs are `@ExperimentalMaterial3Api`; the screen already opts in via
  `@OptIn(ExperimentalMaterial3Api::class)`.
- **`data/tasks/TaskTiming.kt`** — existing formatting/parsing helpers (must be reused, not
  duplicated).
- **`java.time`** on `minSdk = 24` — already enabled via core library desugaring (feature 08).
- **`TaskEditViewModel` / `TaskEditUiState`** — existing `deadlineText: String` and
  `onDeadlineChange` seam (kept intact).
- No new entries in `gradle/libs.versions.toml`.

## Risks

- **UTC-midnight off-by-one-day (highest risk).** `DatePickerState` millis are UTC; a naive local-zone
  read shifts the date backward for negative-offset users. Mitigation: the explicit LocalDate→local
  `LocalDateTime` conversion above, plus a JVM unit test pinned to a non-UTC zone.
- **`readOnly` tap handling.** A `readOnly` `OutlinedTextField` does not receive click events the same
  way an editable one does; opening the dialog must be wired reliably (icon click and/or an
  interaction-source/tap wrapper) so the field is actually openable. Verify on device.
- **Experimental Material 3 API churn.** The picker APIs are experimental; guard with the existing
  `@OptIn` and avoid relying on unstable internals.
- **Preview/test coverage.** The Compose previews currently pass a literal `deadlineText`
  ("24/12/2026 20:30"); after the change the preview should show a locale-formatted display value so
  it reflects reality.
- **Locale/24h correctness.** `TimePicker`'s 12h/24h mode should follow the device setting; confirm
  the displayed field text matches what was picked across a 24h and a 12h locale.

## New Concepts Taught (tutorial lesson 14 — `tutorial/14-selector-fecha.md`, Spanish)

Building on lesson 04 (task form + pure time logic) and lesson 08 (locale-aware date formatting):

1. **Compose dialogs with local UI state** — why dialog open/closed flags and picker state live in
   the composable via `remember`, not in the ViewModel/`UiState` (ephemeral UI vs. durable data).
2. **`rememberDatePickerState` / `rememberTimePickerState`** — Material 3 stateful picker components,
   `DatePickerDialog`, and chaining date → time into one flow.
3. **The UTC→local timezone pitfall** — `selectedDateMillis` is UTC midnight; the correct
   LocalDate + picked time → `ZoneId.systemDefault()` conversion, and why skipping it moves the date a
   day earlier. Ties back to lesson 08's locale/`java.time` material.
4. **`readOnly` + `trailingIcon` with `contentDescription`** — a display-only field that opens a
   picker, and making the icon accessible for TalkBack.
5. **Reuse over duplication** — routing the picked value back through the existing
   `formatDeadlineForInput` / `parseDeadline` seam instead of writing new date code, illustrating how
   a stable ViewModel seam absorbs a UI change.

---

## Approval

Decisions resolved at approval (2026-07-08):

1. **State shape** — **Keep the single `deadlineText: String`**. The picker routes its chosen instant
   through `formatDeadlineForInput(...)` and feeds the canonical string to the existing
   `onDeadlineChange`. No new field on `TaskEditUiState`; the ViewModel seam does not change shape.
2. **"Clear deadline" affordance** — **Include if trivial** (e.g. a small clear/X control that resets
   `deadlineText` to empty); otherwise defer and call it out during implementation.
3. **`INVALID_DEADLINE_FORMAT`** — **Keep** the enum member and its string as the parse contract for
   `parseDeadline`; it simply stops surfacing through the UI in normal picker use.
