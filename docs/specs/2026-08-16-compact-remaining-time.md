# Feature — Tiempo restante en formato compacto y localizable ("2h 38m")

**Status:** Draft — awaiting approval
**Suggested branch:** `feature/compact-remaining-time`
**Tutorial:** `Sí (lección 20b-tiempo-restante-localizable)` — el usuario ya lo decidió. Enseña dónde
puede "nacer" el texto de cara al usuario (una función "pura" que devuelve un `String` ya formateado
es una fuga de presentación hacia el dominio, detectable el día que hay que traducirla), el formateo
de números + unidades por `Locale` (`NumberFormat` + recursos con placeholder frente a la
concatenación en Kotlin), `<string>` con placeholder vs `<plurals>` (la abreviatura "2h" no necesita
plural, "2 horas" sí), y refactorizar un tipo compartido por dos superficies (`PendingTaskRow`) con
los tests como red de seguridad. (El número exacto de lección se confirma al escribirla contra
`tutorial/README.md` + `docs/conceptos-pendientes.md`; se numeró **20b** — el slot 21 estaba reservado para build-release.)
**Type:** Presentation-layer refactor. Moves remaining-time formatting **out of** the domain/data
layer into the UI, behind localizable string resources. **No** backend, **no** API contract change,
**no** Room schema/migration, **no** new dependency, **no** new mockup slice.

---

## Overview

Today three surfaces paint the remaining-time countdown as `hh:mm:ss` (or `mm:ss`): the **task card**
(`TaskRow`), the **home-screen widget** (`PendingTasksWidget`), and the **lock-screen notification**
(`TasksNotificationHelper`). All three get that text from a single function, `formatRemaining(millis)`
in `data/tasks/TaskTiming.kt`, which hardcodes the `:` separator in Kotlin.

This feature changes the *displayed* format to a **compact, no-seconds, localizable** label —
**`2h 38m`** — but the real work is a **layer refactor**. The moment unit letters have to be
translatable ("h"/"m" happen to work for Spanish and English, but a future language wants different
letters and word order), the text can no longer be born in a pure `data/` function with no `Context`
and no string resources. So `formatRemaining` is **deleted from the data layer**, and each surface
builds its own text from the raw milliseconds using the app's existing localization mechanism — the
exact one estimated **duration** already uses (`durationLabel` in `TasksScreen.kt`:
`NumberFormat.getIntegerInstance(locale)` for the numbers, placeholder string resources for the units
and word order).

Concretely:

- `PendingTaskRow` (in `domain/tasks/PendingTaskRows.kt`) stops carrying a pre-formatted
  `remaining: String` and instead carries the **raw `remainingMillis: Long`**. `pendingRowsFor` stops
  formatting text entirely; the shared *rule* it owns (what counts as pending, most-urgent-first
  ordering, the cap of 5) is unchanged.
- A **pure classifier** in `domain/tasks/` turns milliseconds into a small sealed result (time-up /
  under-a-minute / minutes / hours / hours+minutes / days+hours+minutes), fully JVM-testable, reusing
  `durationParts` for the hour/minute split (days derived as `h / 24`, `h % 24`).
- A **single `Context`-based formatter** in `ui/components/` maps that result to `getString(...)` +
  `NumberFormat`, and is called by all three surfaces so they can never drift.

Along the way this **fixes a real inconsistency**: when time runs out, the card and the notification
show "Tiempo agotado" (`tasks_time_up`), but the widget still paints the counter frozen at zero. After
this change all three unify on `tasks_time_up` at exactly zero, because the shared formatter owns that
branch.

This refactor is also the enabler for the later widget redesign: once the widget composes its own text
from millis with a `Context`, it is free to restyle that text without a domain change.

## Goals

- The countdown reads as a compact **`2h 38m`** (no seconds) on all three surfaces: task card, widget,
  notification.
- **Unit letters and word order come from `strings.xml`**, not Kotlin — a future language can supply
  different letters/order by editing resources only, exactly as estimated duration already allows.
- Formatting **leaves the domain/data layer**: `formatRemaining` is removed from `TaskTiming.kt`;
  `PendingTaskRow` carries raw `remainingMillis`; the pure rule in `pendingRowsFor` stays put.
- All three surfaces **agree at exactly zero** on `tasks_time_up` (fixing the widget's frozen-zero
  bug), because one shared formatter owns that branch.
- Existing mechanisms are **reused, not duplicated**: `durationParts` splits the millis; the
  `NumberFormat` + placeholder-resource pattern from `durationLabel` is the template. No new
  `String.format`, no `:` or unit letters concatenated in code.
- Every edge case below (sub-minute, exact zero, over 24h, rounding) has a **defined, tested**
  behaviour.

## User Stories

### US-1 — A compact countdown on the task card
*As someone with ADD/ADHD watching a task, I want a calm "2h 38m" instead of a twitchy "02:38:07", so
that the number stops flickering and I can read my remaining time at a glance.*

**Acceptance criteria**
- A running/pending task with 2 h 38 m left shows **`2h 38m`** on the card (no seconds).
- The label keeps its urgency color (`urgencyLevelFor` + `NeverLateExtras`, unchanged) and its
  `headlineSmall` style.
- At exactly zero the card shows `tasks_time_up` ("Tiempo agotado"), as today.

### US-2 — The widget and notification match the card
*As someone glancing at the widget or lock screen, I want the same "2h 38m" wording and the same
"Tiempo agotado" at zero, so that the three surfaces never disagree.*

**Acceptance criteria**
- The widget row and the notification row render the **same compact label** as the card for the same
  task and instant.
- **At exactly zero, the widget now shows `tasks_time_up`** instead of a zeroed counter — matching the
  card and notification (this is the bug fix).
- The widget still calls out a timed-out row in red (`WidgetTimedOutColor`); it derives that from
  `remainingMillis == 0L`, no longer from a separate pre-baked flag.

### US-3 — Units are translatable
*As a future translator for a language whose time units are neither "h/m" nor in that order, I want to
change the letters and word order in `strings.xml`, so that I never have to touch Kotlin.*

**Acceptance criteria**
- The compact label is assembled from placeholder string resources (`%1$s`/`%2$s`) with numbers
  formatted by `NumberFormat` per `Locale` — no letters, separators, or word order hardcoded in
  Kotlin.
- Resources exist in **both** `values/` (Spanish base) and `values-en/`, kept in sync.

### US-4 — Every edge of the range behaves
*As a user in the final minute (the moment I most need precision) or with a far-off deadline, I want a
sensible, non-jumpy label, so that the counter never lies to me or reads as "done" when it isn't.*

**Acceptance criteria**
- Every row of the **Format matrix** below resolves to the stated label, proven by JVM unit tests on
  the pure classifier.
- The on-screen number **never jumps backward** as time passes (a consequence of truncation, below).

## Format matrix (normative — the implementer follows this table exactly)

The classifier floors to whole minutes via `durationParts(millis)` (which already computes
`totalMinutes = millis / 60_000`). `h` and `m` are those floored parts. Numbers are rendered with
`NumberFormat.getIntegerInstance(locale)`; the letters/order come from the resource.

Below a day the label shows **one or two** units, dropping any zero part (`2h`, `38m`, `2h 38m`).
**At or above 24 h the label enters the days tier and always shows all three parts** — `Xd Yh Zm` —
even when a middle or trailing part is zero (`2d 0h 0m`, `1d 0h 30m`). The rationale for that
asymmetry is locked below.

`d` = `h / 24`, `hOfDay` = `h % 24`, where `h`/`m` are the floored parts from `durationParts(millis)`.

| remainingMillis | d | h | m | Label kind | Resource | ES result | EN result |
|---|---|---|---|---|---|---|---|
| `0` (exactly) | — | — | — | **TimeUp** | `tasks_time_up` (reused) | `Tiempo agotado` | `Time's up` |
| `1 s … 59 s` (0 < millis < 60 000) | 0 | 0 | 0 | **UnderMinute** | `tasks_remaining_under_minute` | `<1m` | `<1m` |
| `1 min … 59 min` (h = 0, m > 0) | 0 | 0 | m | **Minutes** | `tasks_remaining_minutes` | `38m` | `38m` |
| exact hours (0 < h < 24, m = 0), e.g. 2 h | 0 | h | 0 | **Hours** | `tasks_remaining_hours` | `2h` | `2h` |
| 0 < h < 24 and m > 0, e.g. 2 h 38 m | 0 | h | m | **HoursMinutes** | `tasks_remaining_hours_minutes` | `2h 38m` | `2h 38m` |
| ≥ 24 h, e.g. 36 h 10 m | 1 | 12 | 10 | **DaysHoursMinutes** (always 3 parts) | `tasks_remaining_days_hours_minutes` | `1d 12h 10m` | `1d 12h 10m` |
| ≥ 24 h, exact day, e.g. 48 h | 2 | 0 | 0 | **DaysHoursMinutes** (always 3 parts) | `tasks_remaining_days_hours_minutes` | `2d 0h 0m` | `2d 0h 0m` |

**Decisions locked by this table (do not re-litigate in implementation):**

- **Sub-minute → `<1m`, not `0m` and not seconds.** This is exactly the range an ADD/ADHD user most
  needs read correctly, so the two failure modes are avoided deliberately: `0m` reads as "done" for a
  full 60 seconds when it isn't, and showing seconds (`45s … 10s`) brings back the per-second value
  **and width** churn this feature exists to remove. `<1m` is stable (never changes for the whole last
  minute), honest ("under a minute — act now"), and fixed-width. Exactly `0` is the separate **TimeUp**
  case.
- **Exact zero unifies on `tasks_time_up` across all three surfaces.** The shared formatter owns this
  branch, which is what finally brings the widget (today: frozen `00:00`) in line with the card and
  notification.
- **At or above 24 h the label shows days: `1d 12h 10m`, not `36h 10m`.** A 36-hour countdown reads
  more calmly as "just over a day" than as a large hour count. This adds one `d` unit and one
  combined resource (`tasks_remaining_days_hours_minutes`, `%1$sd %2$sh %3$sm`).
- **The days tier always shows all three parts (`Xd Yh Zm`), even zeros.** Below a day the label drops
  zero parts (`2h`, not `2h 0m`) to stay glanceable; at/above a day it does **not** — `2d 0h 0m` and
  `1d 0h 30m` keep every part. The reasons: (a) it needs exactly **one** resource
  (`%1$sd %2$sh %3$sm`) instead of the combinatorial set a drop-zeros rule would require
  (`1d`, `1d 12h`, `1d 30m`, `1d 12h 10m`, …); (b) `1d 30m` (hours silently dropped) is genuinely
  ambiguous to read, whereas `1d 0h 30m` is not; (c) at the day scale the deadline is far, so the full
  breakdown is the useful information and there is no per-second width churn to avoid. The mild
  ugliness of `2d 0h 0m` is rare (only at an exact-day boundary, for at most one minute) and correct.
- **Truncate (floor) to the minute, never round.** Justification: floor means the label never
  overpromises remaining time and the number is **monotonically non-increasing** — it only ever
  ticks down, never jumps backward. Rounding to the nearest minute would make the value jump *up* then
  back down across a boundary (visually alarming) and could momentarily overstate the time left. The
  cost of truncation — "2h 0m" (shown as `2h`) holds for the full first 60 seconds of that hour — is
  acceptable and correct. Floor is also already how `computeRemainingMillis` and `durationParts`
  behave, so the whole stack agrees.
- **New sibling resources, not the duration ones.** Estimated duration uses
  `tasks_duration_hours_minutes` / `_hours` / `_minutes` (rendered "2 h 30 min"). Remaining time gets
  its **own** `tasks_remaining_days_hours_minutes` / `_hours_minutes` / `_hours` / `_minutes`
  (+ `_under_minute`) rendered "1d 12h 10m" / "2h 38m". They are decoupled on purpose: a compact
  counter and a prose duration want different abbreviations ("38m" vs "38 min"), and neither should
  change the other by accident. `tasks_time_up` is the one existing resource **reused**.

## CountdownTicker decision (evaluated, not improvised)

`ui/tasks/CountdownTicker.kt` emits a 1 s tick that drives the in-app card to recompute remaining time
from the wall clock. With seconds gone from the **text**, a 1 s cadence is no longer justified by the
countdown label (which now only changes once a minute). **But** the feature-19 task-card progress bar
(`deadlineProgressFor` → `animateFloatAsState`) still consumes that same tick to drain **smoothly**; a
minute-cadence tick would make the bar lurch in visible steps.

**Decision: keep the 1 s tick.** Its justification simply moves from the countdown text to the
progress bar. Rationale:
- The widget and notification are **not** driven by this ticker (they refresh via WorkManager / writes
  and are already minute-granular), so nothing there changes.
- On the card, the extra recompositions are cheap: the `Text` value is unchanged 59 out of 60 ticks,
  so Compose skips re-drawing it; the progress bar is the real consumer and needs the cadence.
- Lowering the frequency or decoupling text-refresh from bar-refresh adds complexity for no observable
  win and would regress feature 19's smoothness.

The KDoc of `CountdownTicker` (and/or the `TasksViewModel` tick site) is updated to record that the
tick now exists **for the progress bar**, so the next reader doesn't "optimize away" a tick the bar
depends on. Changing the tick's meaning silently is explicitly disallowed; this section is the record
of the choice.

## Acceptance Criteria (consolidated)

### Behavioural
- The card, widget and notification each render the compact label per the **Format matrix**, all three
  agreeing for the same task/instant, including `tasks_time_up` at exactly zero (widget bug fixed).
- `formatRemaining` **no longer exists** in `data/tasks/TaskTiming.kt`; grep confirms no `hh:mm:ss` /
  `":"` countdown formatting and no unit letters concatenated in Kotlin anywhere.
- `PendingTaskRow` carries `remainingMillis: Long` (not a formatted string); `pendingRowsFor` builds no
  text; its pending/ordering/cap rule is unchanged (existing ordering/cap tests still pass).
- The pure classifier covers every Format-matrix row, proven by JVM unit tests (sub-minute, exact zero,
  minutes-only, exact-hours, hours+minutes, days+hours+minutes, exact-day boundary `2d 0h 0m`, the
  `1d 0h 30m` zero-hour-part case, and the truncation boundaries at 59 s / 60 s, the hour boundary, and
  the 24 h boundary).
- The compact label is produced with `NumberFormat` per `Locale` + placeholder resources, verified for
  more than one `Locale`.

### Visual (verified in the Design review step)
- The countdown **does not change width every second** — the single biggest source of `hh:mm:ss` visual
  noise is gone; within a given minute the label is static.
- The label keeps its **urgency color** (`NeverLateExtras` via `urgencyLevelFor`) and `headlineSmall`
  style on the card; the widget keeps its bold/red timed-out treatment; the notification wording is
  unchanged in placement.
- The compact label is legible at the **largest system font scale** on all three surfaces (card,
  widget, notification) without clipping or horizontal overflow.
- No restyle of the card, widget, or notification layout — this is a text-content change within the
  already-✅ "Urgency-colored countdown" slice.

### Definition-of-Done items this feature touches
- **Tests pass.** Pure classifier has JVM unit tests for the full Format matrix; the two tests that
  asserted formatted strings are rewritten (see Technical Approach). `./gradlew :app:testDebugUnitTest`
  green before commit.
- **i18n holds.** Four new resources in **both** `values/` and `values-en/`, kept in sync; no unit
  words concatenated in Kotlin; numbers via `NumberFormat`, not `Long.toString()`. Abbreviated forms
  need no `<plurals>` (that reasoning is part of the lesson).
- **Every state is designed.** The countdown's timed-out state is unified and covered; loading/empty
  are unchanged (`MessageState`, untouched).
- **No migration / no contract / no schema / no dependency change** — explicitly asserted;
  `docs/api/contract.md` and `app/schemas/` are untouched, and that is correct, not a gap.
- **Visual ACs pass and the tracking table reflects reality.** `docs/mockups/README.md`'s
  "Urgency-colored countdown" row gets a note that the countdown text is now the compact `2h 38m`
  format (no new row — see Visual & UX).

## Visual & UX Design

### Master mockup slice — **none new claimed**

This feature refines **text inside an already-✅ slice**: "Urgency-colored countdown (calm / soon /
late)" (owned by feature 17 in `docs/mockups/README.md`). It adds **no new row** and claims no new
slice. `docs/mockups/rediseno-ux-ui.html` is consulted as *direction* only (it shows compact,
calm countdowns); its intent is translated with the app's real theme tokens, never copied from its
HTML/CSS. In the Design review step, the existing "Urgency-colored countdown" row's **note** is updated
to record that the countdown text now renders as compact `2h 38m` (no seconds). Nothing visual is
silently deferred: the seconds are removed by design, not postponed.

### What changes visually, and what does not
- **Changes:** the countdown *string* on card, widget and notification (from `hh:mm:ss`/`mm:ss` to
  `2h 38m`); the widget's zero state (from a frozen counter to "Tiempo agotado").
- **Unchanged:** every layout, color token, style, and touch target. Urgency color, `headlineSmall`
  on the card, the widget's bold/red timed-out styling, and the notification's row/format all stay
  exactly as they are. No new colors, shapes, spacing, or components are introduced.

### Concrete visual acceptance criteria
- The countdown label's **width is stable within a minute** — it no longer twitches once per second
  (verified by observation on the card; inherent for widget/notification, which are minute-granular).
- The label **stays urgency-colored** on the card and **bold/red when timed out** on the widget.
- The label is **legible at max `fontScale`** on the card, the widget, and the notification, with no
  clipping and no horizontal overflow. (The compact form is *shorter* than `hh:mm:ss`, so this is
  easier to satisfy than today, but it is still an AC to verify on each surface.)

### Theme & component reuse
- Reuse `urgencyLevelFor` + `NeverLateExtras` for color (unchanged) and the existing `Text` styles on
  each surface. No one-off styling. The label-building mechanism is the same `NumberFormat` +
  placeholder-resource pattern already proven by `durationLabel`.

## Technical Approach

High level: **move formatting from `data/` to `ui/`, behind resources**, keeping a pure,
JVM-testable classifier in the domain and one shared `Context` formatter for all three surfaces.
Sub-project: **`app/`** only.

### Pure classifier (domain, JVM-testable)
- Add a pure function to `domain/tasks/` (e.g. `RemainingTime.kt`) returning a small **sealed** result:
  `TimeUp` / `UnderMinute` / `Minutes(m)` / `Hours(h)` / `HoursMinutes(h, m)` /
  `DaysHoursMinutes(d, h, m)`. It reuses `durationParts(millis)` for the floor-to-minutes split (no new
  division logic) and derives days from the hour part (`d = h / 24`, `hOfDay = h % 24`), and encodes
  the Format-matrix branching (exact-zero → `TimeUp`; `(0,0)` with millis > 0 → `UnderMinute`;
  `h ≥ 24` → `DaysHoursMinutes`; etc.). This carries the edge-case matrix and is where the "text-free
  rule" now lives — no `Context`, no `Locale`, no strings.
- This is the tutorial's central point: the label's *shape* is a pure decision; only its *rendering*
  (letters, digit shapes) is presentation.

### Shared Context formatter (UI, reused by all three surfaces)
- Add one `Context`-based function to `ui/components/` (e.g. `RemainingTimeLabel.kt`),
  `formatRemainingLabel(context, remainingMillis): String`, that: runs the classifier, formats the
  integer parts with `NumberFormat.getIntegerInstance(locale)` (locale from the context's
  configuration), and maps each kind to its resource via `context.getString(...)`. `TimeUp` maps to
  the reused `tasks_time_up`. This is the single home of remaining-time presentation — mirroring how
  `durationLabel` is the single home of duration presentation.
- **All three surfaces call this one function**, each passing its own `Context`:
  - **Card** (`ui/tasks/TasksScreen.kt`, `TaskRow`): replace the `if (isTimedOut) tasks_time_up else
    formatRemaining(...)` branch with a single `formatRemainingLabel(LocalContext.current,
    uiModel.remainingMillis)` call (the formatter owns the zero branch now). Urgency color/style
    unchanged.
  - **Widget** (`ui/widget/PendingTasksWidget.kt`, `PendingTaskRowContent`): render
    `formatRemainingLabel(LocalContext.current, row.remainingMillis)`; compute the red/timed-out color
    from `row.remainingMillis == 0L`.
  - **Notification** (`ui/notification/TasksNotificationHelper.kt`): the existing `remainingLabel`
    helper collapses to a call to `formatRemainingLabel(context, row.remainingMillis)` (it already had
    the `isTimedOut → tasks_time_up` branch; that logic now lives in the shared formatter).

### Domain type + rule (unchanged rule, changed payload)
- `domain/tasks/PendingTaskRows.kt`: change `PendingTaskRow` to `data class PendingTaskRow(val title:
  String, val remainingMillis: Long)`. **Drop the pre-formatted `remaining: String`.** Recommended:
  also **drop `isTimedOut`** — it is now trivially `remainingMillis == 0L`, so keeping it duplicates
  state; the widget/notification derive it where needed. (Keeping `isTimedOut` is acceptable but
  redundant; the spec's recommendation is to drop it for a single source of truth.)
- `pendingRowsFor` stops calling `formatRemaining`; it maps each `(task, remainingMillis)` to
  `PendingTaskRow(task.title, remainingMillis)`. The pending definition, most-urgent-first ordering,
  and `MAX_PENDING_ROWS` cap are **unchanged**.

### Data layer
- `data/tasks/TaskTiming.kt`: **delete `formatRemaining`**. `durationParts` stays (reused by the
  classifier). `computeRemainingMillis` and everything else in the file are untouched.

### CountdownTicker
- Keep the 1 s tick (see the CountdownTicker decision section). Update its KDoc / the tick site to
  record that the cadence now serves the feature-19 progress bar, not the text.

### i18n (both `values/` and `values-en/`, kept in sync)
New siblings of the duration resources (compact abbreviations):

| Resource | ES (`values/`) | EN (`values-en/`) |
|---|---|---|
| `tasks_remaining_days_hours_minutes` | `%1$sd %2$sh %3$sm` | `%1$sd %2$sh %3$sm` |
| `tasks_remaining_hours_minutes` | `%1$sh %2$sm` | `%1$sh %2$sm` |
| `tasks_remaining_hours` | `%1$sh` | `%1$sh` |
| `tasks_remaining_minutes` | `%1$sm` | `%1$sm` |
| `tasks_remaining_under_minute` | `<1m` | `<1m` |

- Reused unchanged: `tasks_time_up` (`Tiempo agotado` / `Time's up`).
- Values happen to coincide for ES/EN today (the compact letters match), but they are **separate
  resources** so a future language can diverge; the placeholder + `NumberFormat` structure is what
  makes that possible. No `<plurals>` needed — the abbreviated form ("2h") has no singular/plural
  variation (contrast "2 horas"); this is deliberate and part of the lesson.
- The duration resources (`tasks_duration_*`) are **not** touched.

### Tests (rewritten, not deleted)
- `TaskTimingTest.kt`: the assertions on `"00:00"` / `"1:00:00"` reference the deleted
  `formatRemaining` and are **removed/replaced**. New JVM tests target the pure classifier over the
  full Format matrix (exact zero, 1 s / 59 s / 60 s boundaries, minutes-only, exact hours,
  hours+minutes, the 24 h boundary, days+hours+minutes, `2d 0h 0m`, `1d 0h 30m`) — the truncation and
  sub-minute decisions each get a test.
- `PendingTasksWidgetStateTest.kt`: it currently asserts the formatted string inside the row; it now
  asserts `remainingMillis` (value, ordering, cap) instead.
- `NotificationModelTest.kt`: any assertion reading `row.remaining` is updated to `remainingMillis`.
- The localized rendering (`NumberFormat` + `getString`) is verified for at least one non-default
  `Locale` in the same way `durationLabel`'s rendering is proven (instrumented/Compose or a thin
  resource test); the *branching* correctness is fully covered by the pure classifier tests.

### Files in scope
- `app/src/main/java/com/neverlate/data/tasks/TaskTiming.kt` — delete `formatRemaining`.
- `app/src/main/java/com/neverlate/domain/tasks/PendingTaskRows.kt` — `PendingTaskRow` payload +
  `pendingRowsFor` stop formatting.
- `app/src/main/java/com/neverlate/domain/tasks/RemainingTime.kt` — **new** pure classifier.
- `app/src/main/java/com/neverlate/ui/components/RemainingTimeLabel.kt` — **new** shared `Context`
  formatter.
- `app/src/main/java/com/neverlate/ui/tasks/TasksScreen.kt` — `TaskRow` calls the shared formatter.
- `app/src/main/java/com/neverlate/ui/tasks/CountdownTicker.kt` — KDoc only (rationale update).
- `app/src/main/java/com/neverlate/ui/widget/PendingTasksWidget.kt` — render from `remainingMillis`.
- `app/src/main/java/com/neverlate/ui/notification/TasksNotificationHelper.kt` — `remainingLabel`
  delegates to the shared formatter.
- `app/src/main/res/values/strings.xml` + `app/src/main/res/values-en/strings.xml` — four new
  resources.
- Tests: `TaskTimingTest.kt`, `PendingTasksWidgetStateTest.kt`, `NotificationModelTest.kt` (rewritten),
  plus new classifier tests.

## Out of Scope
- **Any backend, API contract, or `TaskDto` change** — this is display-only; `computeRemainingMillis`
  and the wire shape are untouched.
- **Any Room schema change or migration** — no persisted field changes; `NeverLateDatabase` version
  stays as is.
- **Any new dependency** — built from existing Compose/Glance/`NumberFormat` primitives and existing
  helpers.
- **A week/month unit** — the largest unit is the day; a multi-day countdown reads as `12d 6h 30m`,
  not `1w 5d`. Larger units are explicit future work with their own resource.
- **Showing seconds anywhere** — seconds are removed by design; the sub-minute range is `<1m`, not a
  seconds counter.
- **The widget redesign this refactor enables** — restyling the widget is a separate future feature;
  here the widget only changes the *text it composes*, not its layout.
- **Changing the estimated-duration label** (`tasks_duration_*`, "2 h 30 min") — decoupled on purpose;
  not touched.
- **Localizing beyond digits + unit resources** (e.g. RTL-specific tweaks) — the placeholder resources
  already allow word-order changes; anything further is out of scope.

## Dependencies
- **No new libraries, permissions, or backend work.** Everything needed exists.
- **Reused (must be reused, not re-implemented):**
  - `durationParts(millis): Pair<Long, Long>` in `data/tasks/TaskTiming.kt` — the floor-to-minutes
    split, reused verbatim by the new classifier.
  - The `NumberFormat.getIntegerInstance(locale)` + placeholder-resource pattern from `durationLabel`
    in `ui/tasks/TasksScreen.kt` — the template the new formatter follows.
  - `urgencyLevelFor` + `NeverLateExtras` — the countdown's color, unchanged.
  - `tasks_time_up` — the existing timed-out resource, reused.
- **Ordering:** the `PendingTaskRow` payload change touches the widget and notification call sites plus
  their tests; land them together so the module compiles.

## Risks
- **Shared-type change breaks two surfaces at once.** Changing `PendingTaskRow` from a formatted string
  to `remainingMillis` is a breaking change for the widget and notification. *Mitigation:* the pure
  `pendingRowsFor` ordering/cap tests and the two rewritten surface tests are the safety net; all call
  sites are changed in the same branch (this is the tutorial's "refactor a shared type with tests as a
  net" point).
- **The widget zero-state fix is a behaviour change.** Users currently see a zeroed counter on the
  widget at time-up; they will now see "Tiempo agotado". *Mitigation:* this is the intended fix and it
  aligns the widget with the other two surfaces; called out here so it is not a surprise in review.
- **Truncation shows "2h" for the first full minute of an hour.** Someone may expect rounding.
  *Mitigation:* decision is locked and justified (monotonic, never overpromises) and covered by a
  boundary test; the alternative (backward-jumping counter) is worse for this audience.
- **Sub-minute `<1m` loses seconds precisely when precision feels wanted.** *Mitigation:* reasoned in
  the Format matrix — a per-second seconds counter reintroduces exactly the width/value churn the
  feature removes, and `0m` reads as "done"; `<1m` is the stable, honest middle. Covered by a test.
- **JVM-testing localized strings.** `getString` needs a `Context`. *Mitigation:* the branching lives
  in the pure classifier (fully JVM-tested); only the thin `getString` + `NumberFormat` mapping needs a
  context, verified the same way `durationLabel` is.
- **Someone later "optimizes" the 1 s tick.** With no seconds on screen the tick looks pointless.
  *Mitigation:* the CountdownTicker KDoc is updated to state the tick now serves the progress bar; the
  decision section is the record.
- **Overall blast radius is contained:** no persistence, wire, or scheduling code changes; the change
  is text formatting plus one shared-type payload swap.

---

## Approval

Please review this specification. Approval covers **behaviour, look, and the tutorial decision**
(`Sí (lección 21)`). In particular the locked decisions are part of what is signed off: sub-minute →
`<1m`, exact zero → `tasks_time_up` on **all three** surfaces (widget bug fixed), **≥ 24 h shows days**
as `1d 12h 10m` (days tier always renders all three parts, e.g. `2d 0h 0m`), **truncate** to the
minute, **new sibling** `tasks_remaining_*` resources rather than reusing the duration ones, and
**keeping the 1 s CountdownTicker** for the progress bar.
Implementation will not begin until you explicitly approve.
