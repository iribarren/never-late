# Feature — Make priority actually do something: sort, filter, and reach every surface

- **Status:** Approved
- **Date:** 2026-08-17
- **Branch (suggested):** `feature/priority-sorting`
- **Original title (user's words):** *"Que la prioridad sirva para algo: ordenar, filtrar y llegar a
  todas las superficies"*
- **Prompt origen:** [`docs/prompts/prioridad-operativa.md`](../prompts/prioridad-operativa.md)
  (row 3 of [`docs/diferidos.md`](../diferidos.md))
- **Type:** Behaviour + UI change on `app/` only. **No** backend change, **no** API contract change
  (priority already crosses the wire, contract §4), **no** Room schema change or migration (the
  `priority` column exists since DB v5, `MIGRATION_4_5`), **no** new permission, **no** new
  dependency.
- **Tutorial:** **No** — product change only, confirmed by the user via `AskUserQuestion`
  ("No (solo producto)"). In-memory filter/sort/group is already lesson `03b`, and priority as data
  is already `13b`; what this feature adds is a *modelling decision* (D4 below), which is documented
  in this spec and in `docs/arquitectura.md` rather than in a lesson. **No lesson is written, no
  lesson number is reserved**, and `/doc-check` must not report a missing lesson for this branch.

---

## Overview

Priority is, today, **decorative**. A user picks it in the edit form, Room stores it, the task card
draws an 8 dp colored dot, and the widget draws a `!`/`!!`/`!!!` marker — and that is the whole of
it. Priority does not sort, does not filter, does not group, and appears in neither the lock-screen
notification nor the stats screen.

That is a broken promise in the user's face: the app invites them to declare "this one matters", and
then behaves exactly as if they hadn't. For the ADD/ADHD audience this app targets, that is worse
than not offering priority at all — the act of triaging is effortful, and effort that produces no
change in the product teaches the user to stop triaging.

Feature `13b` recorded this as deferred in
[`docs/mockups/README.md`](../mockups/README.md) ("priority-based sort/filter/group, priority×urgency
color blending, priority on **notification/stats**"). Feature `05b` closed the **widget** third of
it. This feature closes the rest, and decides — in writing, up front — the two modelling questions
that closing it forces:

1. what the full sort precedence stack is once priority joins it (D3), and
2. what `ShapedTaskList.Grouped` becomes when grouping gains a second axis (D4).

It also fixes a standing accessibility gap the widget already solved and the app did not: **in the
app, priority is a purely chromatic cue** (a colored dot). The widget's `!`/`!!`/`!!!` convention
becomes app-wide (D8).

---

## Goals

- A user who marks a task **HIGH** can, in two taps, see only what matters — or see the whole list
  with the important work at the top.
- Priority is legible **without perceiving color**, on every surface: card, widget, notification.
- The two deferrals `13b` wrote down (notification, stats) are **closed or consciously re-deferred
  with reasons**, never left silently pending.
- No surface invents a second definition of anything: one priority→color role mapping
  (`domain/tasks/ColorRole.kt`), one priority→marker mapping, one priority→label mapping.
- `TaskListShaping.kt` stays pure and JVM-testable; every new rule is provable without an emulator.

---

## User Stories

### US-1 — Sort by priority
*As a user with a long task list, I want to sort by priority, so that the tasks I flagged as
important are at the top regardless of when they are due.*

**Acceptance criteria**
- A **"Prioridad"** sort chip sits alongside "Fecha límite" and "Título" in `TaskListControls`.
- Selecting it re-shapes the list on the next emission, with no visible delay (sort criteria are
  **not** debounced — only the text query is; see `TasksViewModel.debouncedQuery`).
- Ascending direction means **most important first** (`HIGH → MEDIUM → LOW → NONE`); the direction
  toggle flips it to `NONE → LOW → MEDIUM → HIGH`. See D3 for why "ascending = act on this first"
  is the consistent reading.
- Completed tasks still sort **after** every pending task, in both directions (unchanged from `04c`).
- Ties within a priority are broken deterministically: soonest deadline first (nulls last), then
  title A→Z. Two tasks never swap places between two identical renders.

### US-2 — Filter by priority
*As a user whose list has grown long, I want to show only tasks of the priorities I care about, so
that I can work from a short list instead of scanning everything.*

**Acceptance criteria**
- Four **filter chips** (Alta / Media / Baja / Ninguna) are available in the same `FlowRow`, using
  `Priority.labelRes()` for labels.
- The chips are **multi-select**: any subset can be active. **No chip selected = show everything**
  (see D5).
- The priority filter composes with the text query (both apply, in any combination).
- Filtering to zero visible tasks shows `TasksUiState.NoResults` (not `Empty`), and its action
  clears **both** the text query and the priority filter — a user can never be stranded in a
  "no results" screen whose only escape clears the wrong filter.
- Selecting/deselecting a chip is immediate (not debounced).

### US-3 — Group by priority
*As a user, I want to group the list by priority as an alternative to grouping by urgency, so that I
can see my work organized by what I decided matters, not only by what the clock decided.*

**Acceptance criteria**
- Grouping is now a **three-state axis** — none / by urgency / by priority — exposed as two
  independently toggleable chips ("Agrupar por urgencia", "Agrupar por prioridad"). Selecting one
  deselects the other; tapping the selected one returns to ungrouped.
- Priority sections render in fixed display order **Alta → Media → Baja → Sin prioridad**; empty
  sections are dropped.
- Section headers are localized (`strings.xml` + `values-en/strings.xml`), styled exactly like the
  existing urgency headers (`titleSmall`, `onSurfaceVariant`).
- Sorting still happens **inside** each section, never across the whole list first (unchanged rule
  from `03b`).
- A completed task groups under its **real** priority (not forced into a catch-all), unlike urgency
  grouping which forces completed tasks into `Calm` — see D6.

### US-4 — Priority on the lock-screen notification
*As a user glancing at my lock screen, I want to see which of the listed tasks are the important
ones, so that I can act without unlocking the phone.*

**Acceptance criteria**
- Each `InboxStyle` line for a non-`NONE` priority task ends with the **compact marker**
  (`!` / `!!` / `!!!`), never the full word.
- The marker is a **trailing** token, after the remaining-time label, so system truncation eats the
  marker before it ever eats the title or the remaining time (D7).
- `NONE` tasks render **byte-for-byte the same line as today**.
- The collapsed `setContentText` line and the redacted `publicVersion` are **unchanged**.
- Row ordering in the notification is **unchanged** (most-urgent-first; priority does not reorder
  passive surfaces — see Out of Scope).

### US-5 — One priority-aware statistic
*As a user reviewing my week, I want to know how many of the tasks I myself flagged as important I
actually finished, so that I can tell a genuinely productive week from a busy one.*

**Acceptance criteria**
- The Stats screen gains **exactly one** new card: **"Alta prioridad completadas"** /
  *"High-priority completed"* — the count of `HIGH` tasks completed within the current ISO week.
- It is derived from the tasks `weeklyStatsFor` already classified as completed-this-week (no second
  pass, no second definition of "this week").
- `0` is a legitimate, meaningful value and is shown as `0` (unlike `onTimePercent`, which shows
  "—" when undefined).
- Deleted tasks are excluded, consistently with every other number on that screen.

### US-6 — Priority readable without color, in the app
*As a user who does not reliably distinguish the indicator colors, I want to read a task's priority
from its shape, so that the card tells me as much as the widget already does.*

**Acceptance criteria**
- The task card's chromatic-only dot is replaced by the same **`!`/`!!`/`!!!` marker** the widget
  uses, colored with the same role (`Priority.indicatorColor()`).
- The marker keeps the existing `tasks_priority_content_description` semantics ("Prioridad: Alta"),
  so screen readers announce the word, not the glyph.
- The marker is still suppressed on completed rows ("completed styling wins", unchanged from `13b`).
- The widget, the notification and the card all read the marker from **one** mapping
  (`Priority.markerRes()`), not three.

---

## Acceptance Criteria (cross-cutting / Definition of Done)

- `./gradlew :app:testDebugUnitTest` is green. New JVM tests cover: the full precedence stack (with
  fixtures where **priority and urgency deliberately disagree**, not fixtures where they happen to
  coincide), the priority filter (empty set, single, multiple, combined with a text query), priority
  grouping (display order, empty sections dropped, completed tasks in their real section), and the
  new stats metric (week boundaries, `HIGH`-only, deleted excluded).
- No Room schema change → **no** version bump, **no** migration, **no** new `app/schemas/N.json`.
  If any of those appear in the diff, the implementation drifted from this spec.
- No `docs/api/contract.md` change (priority already on the wire, unchanged shape).
- Every new user-facing string exists in **both** `values/` (Spanish base) and `values-en/`.
- Loading / empty / error states on Tasks and Stats keep using `ui/components/MessageState`; the new
  filter does not introduce a fourth state (a filtered-to-zero list is the existing `NoResults`).
- All new touch targets ≥ 48 dp via `minimumInteractiveComponentSize()`.
- `docs/mockups/README.md` updated (see Visual & UX Design), and D3/D4/D7 recorded in
  `docs/arquitectura.md`.

---

## Visual & UX Design

### Mockup slice

**No slice of [`docs/mockups/rediseno-ux-ui.html`](../mockups/rediseno-ux-ui.html) is claimed.** The
master mockup has **no priority element and no filter/sort/group surface at all** — both the `13b`
priority row and the `03b` controls row in the tracking table are already recorded as
"— not a mockup slice". This feature stays inside those two net-new areas and inside the `04c` stats
surface (also net-new). It introduces **no new visual language**: every new element is an instance of
a pattern the app already ships (`FilterChip` in a `FlowRow`, `titleSmall`/`onSurfaceVariant` section
header, `StatCard`).

The mockup is still read as **direction** for spacing and hierarchy on the Tasks screen; nothing is
copied from its HTML/CSS.

### Deferred, and to where

- **Priority × urgency blended color** — stays deferred from `13b`. This feature deliberately keeps
  the two channels separate (urgency owns the countdown color and the progress bar; priority owns the
  leading marker). Remains a pending note on the `13b` row.
- **Persisting sort/filter/group across process death** — the criteria still live only in
  `TasksViewModel`. This feature *widens* what is lost on restart (a filter is more annoying to lose
  than a sort), which strengthens the case for the already-written
  [`docs/prompts/preferencias-lista-persistentes.md`](../prompts/preferencias-lista-persistentes.md)
  (row 8 of `diferidos.md`). Explicitly **not** taken here.
- **Priority affecting widget/notification row ordering** — deferred indefinitely; see Out of Scope.
- **A dedicated "completed" grouping section** — still out (unchanged from `04c`).

### Tracking table updates (mandatory, in the Design review step)

- **`13b` row** — remove "priority-based sort/filter/group" and "priority on **notification/stats**"
  from its deferred list (this feature delivers all three) and note the card's dot → marker change.
  What stays deferred on that row is **only** priority×urgency color blending.
- **`05b` widget row** — note that `widget_priority_marker_*` was renamed to `priority_marker_*` and
  the widget's local marker `when` now calls the shared `Priority.markerRes()`. **No visual change to
  the widget**, so its status is untouched.
- **`03b` controls row** — note the added priority sort chip, the four priority filter chips, and the
  grouping toggle becoming a three-state axis. Still no mockup slice claimed.
- **`04c` stats row** — note the fourth stat card.

### Visual acceptance criteria

1. `TaskListControls`' `FlowRow` **does not overflow or clip at the largest system font scale** on a
   compact phone width: chips reflow onto as many lines as needed, and the whole controls block stays
   scrollable/visible above the list. Verified at 200% font scale on the smallest supported width.
2. Every new chip has a touch target **≥ 48 dp** (`minimumInteractiveComponentSize()`), matching the
   existing sort chips exactly — no new chip is visually or physically smaller.
3. Selected filter chips use the **stock Material 3 `FilterChip` selected treatment** (leading check +
   `secondaryContainer`), not a per-priority background — priority color stays confined to the marker
   so the chips remain readable and the palette does not compete with urgency.
4. **Priority section headers are localized** and visually identical to urgency headers
   (`titleSmall`, `onSurfaceVariant`, same 16 dp / 8 dp padding) — a user cannot tell the two axes
   apart by header styling, only by text.
5. **Priority is readable without color in the app**: the card marker (`!`/`!!`/`!!!`) carries the
   level by *shape*; rendering the card in grayscale still distinguishes Alta/Media/Baja.
6. The marker does **not** widen the task row: it occupies the same leading slot the 8 dp dot did,
   and long titles keep their existing truncation behaviour at max font scale.
7. The new stat card is **visually identical** to the three existing ones (`headlineMedium` number +
   `bodyMedium` label + `BrandIconChip`), and the stats column **reflows without clipping** at the
   largest font scale with four cards instead of three.
8. Notification lines with a marker still fit the collapsed shade for typical titles; the marker is
   the **first thing truncated** on a long line (verified by reading the rendered line, not only the
   model).
9. No new color, no new hex, no new typography token anywhere in the diff — everything resolves
   through `ui/theme/` and `domain/tasks/ColorRole.kt`.

---

## Data Model / API Decisions

### D1 — `Priority` gains an explicit `rank`; the KDoc invariant stays true

`data/tasks/Priority.kt`'s KDoc states, deliberately, that **nothing relies on the enum's ordinal**
(the `@TypeConverter` persists `name`, so a future reorder/insert can never corrupt rows). Sorting by
`ordinal` would silently make that sentence false.

**Decision:** declare the order as data, not as declaration position.

```kotlin
enum class Priority(val rank: Int) {
    NONE(0), LOW(1), MEDIUM(2), HIGH(3);
}
```

- Comparisons use `rank`, never `ordinal`. The KDoc's guarantee survives verbatim.
- Serialization is unaffected: kotlinx.serialization still encodes an enum by `name`, and the Room
  converter still stores `name` — the constructor parameter is invisible to both. **No contract
  change, no migration.**
- A future `URGENT` inserted between `MEDIUM` and `HIGH` gets a rank without renumbering storage.
- The KDoc is extended with one sentence naming `rank` as the ordering channel, so the next reader
  finds the rule where the warning is.

**Rejected:** comparing `ordinal` and rewriting the warning. It trades a compile-time-safe property
for a comment that must be re-read correctly forever, and couples ordering to declaration position —
exactly what `13b` chose to avoid.

### D2 — `TaskListCriteria` grows; no parallel ViewModel flow

`TaskListCriteria` is already "everything the controls configure", and `TasksViewModel` already
`combine`s exactly three inputs (tasks, debounced query, criteria). Both new controls go **into that
object**:

```kotlin
data class TaskListCriteria(
    val sortField: TaskSortField = TaskSortField.Deadline,
    val direction: SortDirection = SortDirection.Ascending,
    val groupAxis: TaskGroupAxis = TaskGroupAxis.None,   // was: grouped: Boolean
    val priorityFilter: Set<Priority> = emptySet(),       // new; empty = show all
)

enum class TaskSortField { Deadline, Title, Priority }    // Priority is new
enum class TaskGroupAxis { None, Urgency, Priority }      // replaces the Boolean
```

The text query stays a separate `StateFlow` (unchanged from `04b`) because it alone is debounced.
No new `StateFlow`, no new `combine` arm — the pipeline's shape is untouched.

`onToggleGrouping()` is replaced by `onGroupAxisChange(axis: TaskGroupAxis)`; the screen's two
grouping chips call it with `Urgency`/`Priority`, and re-tapping the selected chip calls it with
`None`. `onPriorityFilterToggle(priority: Priority)` flips membership in the set with `.copy(...)`,
the same pattern every other intent uses.

### D3 — The full sort precedence stack, written down

The comparator is, in order (this is the complete list — nothing implied):

1. **Completed last.** `compareBy { task.completedAt != null }` — unchanged primary key from `04c`.
   Done work never competes for a position.
2. **The selected sort field**, in the selected direction:
   - `Priority` → by `rank`. **Ascending = most important first** (`HIGH` → `NONE`).
   - `Deadline` → `nullsLast()`, unchanged, including the `nullsLast(reverseOrder())` treatment for
     descending so a null deadline sorts last in *both* directions.
   - `Title` → case-insensitive, unchanged.
3. **First tiebreak: deadline ascending, nulls last.** Only reached when the field above left two
   tasks equal — which, for `Priority`, is the common case (many tasks share a level). Within a
   priority level, "what's due soonest" is the only useful next question.
4. **Final tiebreak: title A→Z, case-insensitive.** Guarantees a **total order**, so the list never
   reshuffles between two renders of identical data (which would defeat `Modifier.animateItem()` and
   look like a bug).

Two deliberate non-decisions, stated so they are not mistaken for oversights:

- **Priority is not a global secondary key.** When the user sorts by *deadline*, priority does
  **not** boost anything. A user who asked for chronological order gets chronological order;
  a silent "…but important ones first" makes the list unpredictable and the sort control a lie.
  Priority influences order only when it is the selected field (US-1) or the selected group axis
  (US-3).
- **"Ascending = most important first"** inverts `rank`'s numeric direction. It is chosen for
  consistency of *meaning* across the three sort fields: in this app, ascending already means "the
  ones to deal with first are on top" (soonest deadline, A→Z). Making `Priority` ascending mean
  `NONE`-first would be numerically tidy and behaviourally absurd. The comparator carries a comment
  saying exactly this, since it is the one place the code reads "backwards".

### D4 — `ShapedTaskList.Grouped` is **generalized**, not duplicated

The hard call. `Grouped(sections: Map<UrgencyLevel, List<TaskUiModel>>)` is consumed by
`ShapedTaskListView`'s exhaustive `when` and by `SectionHeader(level: UrgencyLevel)`.

**Decision: generalize the section key; keep exactly two `ShapedTaskList` variants.**

```kotlin
sealed interface TaskGroupKey {
    data class ByUrgency(val level: UrgencyLevel) : TaskGroupKey
    data class ByPriority(val priority: Priority) : TaskGroupKey
}

data class TaskSection(val key: TaskGroupKey, val tasks: List<TaskUiModel>)

sealed interface ShapedTaskList {
    data class Flat(val tasks: List<TaskUiModel>) : ShapedTaskList
    data class Grouped(val sections: List<TaskSection>) : ShapedTaskList
}
```

**Why this over a second `Grouped` variant (`GroupedByUrgency` / `GroupedByPriority`):**

- The renderer's `when` over `ShapedTaskList` answers *"does this list have section headers?"* — a
  question with exactly two answers, today and after a third axis. Splitting the variant per axis
  would **duplicate the entire grouped `LazyColumn` body** (headers, `items(key = ...)`, `TaskRow`
  wiring, `animateItem()`) once per axis, and a third axis would duplicate it a third time. That is
  the kind of copy the "extend, don't duplicate" rule exists to prevent.
- The axis-specific decision is **only the header label**. Pushing the sealed dispatch down to
  `SectionHeader(key: TaskGroupKey)` puts the exhaustive `when` exactly where the per-axis knowledge
  lives — and it is still exhaustive, so a third axis still fails to compile until its label exists.
- `List<TaskSection>` instead of `Map`: display order is a **decision** this file already makes
  explicitly (`URGENCY_DISPLAY_ORDER`). A `List` makes "these are in display order" a property of the
  type rather than a property of how the `Map` happened to be built.

**The tradeoff, stated honestly:** the compiler no longer forces the *screen-level* `when` to
acknowledge a new axis — only the header-level one. If a future axis needed more than a different
header (say, a sticky header or a per-section action), that requirement would not be surfaced by a
compile error; it would have to be noticed. That is judged acceptable because every axis this feature
or the mockup contemplates differs *only* in its label, and because the alternative pays for that
narrow safety with wholesale duplication of the list body.

`isEmpty()` becomes `sections.isEmpty()` over the list (still true only when nothing survived
filtering, since empty sections are dropped before construction).

### D5 — The priority filter is **multi-select**, empty = show all

`filteredBy(query)` is left untouched (single responsibility, and its "blank query returns `this`"
optimization stays). A sibling is added:

```kotlin
fun List<TaskUiModel>.filteredByPriority(priorities: Set<Priority>): List<TaskUiModel> =
    if (priorities.isEmpty()) this else filter { it.task.priority in priorities }
```

`shapedBy` composes them: `filteredBy(query).filteredByPriority(criteria.priorityFilter)`.

**Multi-select over single-select** because the real question users ask is *"show me what matters"*,
which is `HIGH + MEDIUM` far more often than any single level. Single-select would force two
interactions and a mental model ("only one at a time") that the `FilterChip` component does not
suggest. `Set<Priority>` also makes "no filter" representable as `emptySet()` without a sentinel or a
nullable — and `emptySet()` is the correct default, i.e. the pre-feature behaviour.

`NONE` is offered as a chip (labelled "Ninguna") so the filter is **total**: "show me everything I
haven't triaged yet" is a legitimate ADHD workflow (it's the backlog of unmade decisions), and a
filter that can express three of four values invites the "where did my tasks go?" bug report.

### D6 — Completed tasks group under their **real** priority

`groupedByUrgency` forces a completed task into `Calm`, because a completed task *has no meaningful
urgency* — its countdown isn't even rendered. **Priority is different: it stays true after
completion.** A finished HIGH task belongs in the Alta section, where `sortedBy`'s completed-last key
already sinks it below the pending ones. Inventing a "completed" bucket on this axis only would make
the two groupings behave differently for no user-visible benefit, and a dedicated completed section
remains out of scope (unchanged from `04c`).

### D7 — Notification: `05b`'s D2 is **partially rebutted**, and the marker ships as a trailing token

`05b`'s D2 declined to render priority on the lock screen. Its argument, quoted in substance: the
`InboxStyle` lines are already truncated by the system, and a third text token pushes *which task* and
*how long is left* toward the ellipsis; "the widget has layout, the notification has a text budget".

That argument is **correct about the mechanism and wrong about the conclusion**, for two reasons:

1. **Its concrete harm is entirely avoidable by position.** The harm D2 names is "pushes the two facts
   that matter toward the ellipsis". Truncation eats the **tail** of a line. A marker placed *after*
   the remaining-time label therefore cannot displace either fact — it is itself the first thing
   sacrificed on a long line. The objection dissolves rather than being overruled: on any line that
   fits, the user gains the cue; on any line that doesn't, they lose only the cue, and the line reads
   exactly as it does today.
2. **Its premise changed with this feature.** D2 also argued that priority merely "restates
   importance in a second, weaker channel" because the notification is already most-urgent-first.
   That held while priority was decorative everywhere. Once priority sorts, filters and groups (US-1
   to US-3), it is a fact the user actively maintains — and a passive surface that omits a fact the
   user maintains is the exact inconsistency this feature exists to remove.

What D2 got right and this spec **preserves unchanged**:

- The **compact marker only** (`!`/`!!`/`!!!`), never the word "Alta" — the text budget is real.
- **Row ordering is untouched** (`pendingRowsFor` stays most-urgent-first). Priority informs, it does
  not reorder passive surfaces.
- The collapsed `contentText`, the `publicVersion` and `PendingTaskRow`'s shape are untouched;
  `priority` was already added to the row by `05b` precisely so this could be a rendering-only change.

Implementation: one new format string pair (`notification_row_format_priority` = `"%1$s — %2$s %3$s"`)
used only for non-`NONE` rows; `NONE` rows keep `notification_row_format` verbatim, so their existing
tests pass untouched.

### D8 — One marker mapping, app-wide; the card's dot becomes the marker

`widget_priority_marker_low/medium/high` is renamed to **`priority_marker_low/medium/high`** in both
locales — the convention is no longer widget-specific. A single non-composable mapping joins
`labelRes()` in `ui/tasks/PriorityUi.kt`:

```kotlin
@StringRes
fun Priority.markerRes(): Int? = when (this) {
    Priority.NONE -> null
    Priority.LOW -> R.string.priority_marker_low
    ...
}
```

The widget's local `when` and the notification both call it, so the three surfaces cannot drift —
the same shape `ColorRole.kt` already gives the color mapping.

On the task card, the 8 dp dot is **replaced** by the marker (`labelSmall`, colored with
`Priority.indicatorColor()`, same leading slot, same
`tasks_priority_content_description` semantics, still suppressed on completed rows). Replaced rather
than supplemented: keeping both doubles the leading chrome and widens the row for a signal that is
already fully carried by shape **and** color in a single glyph.

---

## Technical Approach

Order matters — the model lands first, then the surfaces.

1. **`data/tasks/Priority.kt`** — add `rank`, extend the KDoc (D1).
2. **`domain/tasks/TaskListShaping.kt`** — `TaskSortField.Priority`, `TaskGroupAxis`,
   `priorityFilter`, `filteredByPriority`, `groupedByPriority`, the new `sortedBy` precedence stack,
   the generalized `TaskGroupKey`/`TaskSection`/`Grouped`, and the two display-order lists. Pure, no
   Android imports — all of it JVM-tested before any UI moves.
3. **`domain/tasks/TaskStats.kt`** — `WeeklyTaskStats.highPriorityCompletedThisWeek`, derived from
   the existing `completedThisWeek` list.
4. **`ui/tasks/TasksViewModel.kt`** — `onGroupAxisChange`, `onPriorityFilterToggle`, and the
   `NoResults` clear action clearing both filters. No new flow.
5. **`ui/tasks/TasksScreen.kt`** — the new chips in the existing `FlowRow`; `ShapedTaskListView`'s
   `Grouped` branch iterating `List<TaskSection>`; `SectionHeader(key: TaskGroupKey)`; the card's
   dot → marker.
6. **`ui/tasks/PriorityUi.kt`** — `markerRes()`; **`ui/widget/`** switches to it.
7. **`ui/notification/TasksNotificationHelper.kt`** — the trailing marker (D7).
8. **`ui/stats/StatsScreen.kt`** — the fourth `StatCard`.
9. **Strings** — both locales, including the marker rename.
10. **Docs** — `docs/mockups/README.md` (four rows), `docs/arquitectura.md` (D3, D4, D7).

Agents: `mobile-engineer` for 1–9, `qa-engineer` for the JVM suites named in the Acceptance Criteria.

---

## Out of Scope

- **Priority × urgency blended color** — stays deferred from `13b`.
- **Changing how priority is chosen** in the task edit form — the chip selector is untouched.
- **Priority reordering the widget or the notification.** `pendingRowsFor` remains
  most-urgent-first. Those surfaces answer "what is about to bite you", which is a clock question;
  making them priority-weighted would need its own product decision and its own spec.
- **Persisting criteria** across process death (see `preferencias-lista-persistentes.md`).
- **Priority influencing reminder scheduling** (lead time, time-up alerts) — unrelated subsystem.
- **A dedicated "Completadas" grouping section**, and grouping by any third axis.
- **Backend, contract, Room schema, new dependencies** — none, by construction.
- **Multi-axis grouping** (priority *within* urgency). One axis at a time; the model in D4 would
  allow nesting later but this feature does not build it.

---

## Dependencies

Everything required already exists on `master`; nothing blocks a start.

- `PendingTaskRow.priority` — shipped by `05b`.
- `domain/tasks/ColorRole.kt` (`priorityColorRole`) — shipped by `widget-hilt-color-token`.
- `Priority.labelRes()` / `indicatorColor()` and the `priority_*` strings in both locales — `13b`.
- `widget_priority_marker_*` strings — `05b` (renamed here).
- `TaskListCriteria`, `shapedBy`, the `FilterChip`/`FlowRow` control pattern — `03b` / `04b`.
- `weeklyStatsFor` + `StatCard` — `04c`.

---

## Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | **Chip overflow.** The `FlowRow` goes from ~4 chips to ~9. At 200% font on a small phone the controls block could eat most of the screen. | Visual AC 1 verifies reflow at max font on the smallest supported width. If it proves oppressive, the fallback (not taken now) is collapsing the filter chips behind a single "Prioridad" expander — a scope change requiring re-approval, not an improvised fix. |
| R2 | **`NoResults` dead end.** Today the clear action only clears the text query; with a priority filter active, a user could tap it and still see nothing. | Explicit AC in US-2: the action clears **both**. Covered by test. |
| R3 | **Ordinal invariant re-broken later.** Someone adds a comparison on `ordinal` after this lands. | `rank` (D1) plus the KDoc sentence naming it; a JVM test asserts `rank` ordering independently of declaration order. |
| R4 | **Sort instability.** Ties rendering in a different order between frames would fight `animateItem()`. | Step 4 of D3 makes the comparator a **total** order; tested with equal-priority, equal-deadline fixtures. |
| R5 | **Notification truncation regression.** The marker could push a line over on some OEM shade. | D7's trailing position bounds the damage to the marker itself; AC 8 verifies on a real shade, and `NONE` rows are byte-identical so existing notification tests act as a regression guard. |
| R6 | **String rename misses a locale or a call site.** `widget_priority_marker_*` → `priority_marker_*`. | Resource renames are compile-time-checked in Kotlin; both `values/` and `values-en/` are edited in the same commit, and a `grep` for the old name is part of the review. |
| R7 | **`ShapedTaskList.Grouped` fanout.** The type change touches every consumer, including tests. | Contained: `TasksScreen` (one branch + `SectionHeader`) and `TaskListShapingTest`. The model change (step 2) lands and compiles before any UI work starts. |
| R8 | **Tests that accidentally agree.** Fixtures where high priority *also* has the nearest deadline prove nothing about precedence. | Explicit test requirement: fixtures where priority and urgency **contradict**. |

---

## Approval

Please review and approve this spec before implementation begins. Approval covers all three things
the workflow signs off together: **behaviour** (D3, D5, D7), **look** (the Visual & UX Design section
and its nine visual acceptance criteria), and **the tutorial decision** (`Tutorial: No` — no lesson
will be written for this feature).

The decisions most worth a second opinion before coding: **D4** (generalizing `Grouped` vs. two
variants), **D7** (rebutting `05b`'s D2 on the notification), and **D3**'s choice that priority never
acts as a hidden secondary sort key.
