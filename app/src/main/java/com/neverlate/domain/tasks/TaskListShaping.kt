package com.neverlate.domain.tasks

import com.neverlate.data.tasks.Priority
import com.neverlate.ui.tasks.TaskUiModel

/**
 * Pure, Android-free list shaping for the Tasks screen (feature 03b) — filter, sort, and group,
 * all performed **in memory** over the list [com.neverlate.ui.tasks.TasksViewModel] already has
 * loaded (no new Room query, no network involved). This mirrors the same "keep the decision in
 * plain Kotlin, let the platform layer stay a thin shell around it" split [ReminderPlanning.kt]
 * already uses for reminder scheduling — everything below takes plain values and returns plain
 * values, so a JVM test can cover it with no emulator.
 *
 * Feature `priority-sorting` extends every axis here with a second dimension, priority, alongside
 * the existing time-driven one (urgency/deadline) — see D2-D6 in
 * `docs/specs/2026-08-17-priority-sorting.md` for the modelling decisions this file implements.
 *
 * This file doubles as this project's lesson vehicle for core Kotlin language features the
 * codebase already leans on without ever naming them: null-safety, `when` as an exhaustive
 * expression, higher-order collection functions + `Comparator`, and scope functions. See
 * `tutorial/03b-filtro-orden-memoria.md`.
 */

/** Which field to sort the task list by (US-2, US-1 of `priority-sorting`). */
enum class TaskSortField {
    Deadline, Title, Priority;

    companion object {
        /**
         * `persisted-list-preferences` (D4): parses the stored `String` back into a
         * [TaskSortField], tolerating anything unexpected — same pattern as
         * [com.neverlate.data.ThemeMode.fromStorage]. A missing/unknown value falls back to
         * [Deadline] (the same default [TaskListCriteria] already declares) rather than throwing,
         * so a renamed/removed constant from a future version never crashes the Tasks screen.
         */
        fun fromStorage(value: String?): TaskSortField =
            entries.firstOrNull { it.name == value } ?: Deadline
    }
}

/** Ascending ("soonest deadline first" / "A→Z" / "most important first") or descending direction. */
enum class SortDirection {
    Ascending, Descending;

    companion object {
        /** `persisted-list-preferences` (D4): tolerant parsing, defaulting to [Ascending] — see
         *  [TaskSortField.Companion.fromStorage]'s KDoc for the full reasoning. */
        fun fromStorage(value: String?): SortDirection =
            entries.firstOrNull { it.name == value } ?: Ascending
    }
}

/**
 * The three-state grouping axis (US-3 of `priority-sorting`): no grouping, urgency sections
 * (unchanged from 03b), or priority sections (new). Replaces the old `Boolean` `grouped` flag on
 * [TaskListCriteria] — a `Boolean` could only ever mean "grouped by urgency or not", which stops
 * being true the moment a second axis exists.
 */
enum class TaskGroupAxis {
    None, Urgency, Priority;

    companion object {
        /** `persisted-list-preferences` (D4): tolerant parsing, defaulting to [None] — see
         *  [TaskSortField.Companion.fromStorage]'s KDoc for the full reasoning. */
        fun fromStorage(value: String?): TaskGroupAxis =
            entries.firstOrNull { it.name == value } ?: None
    }
}

/**
 * Everything the Tasks screen's sort/group/filter controls (sort chips, direction toggle, group
 * chips, priority filter chips) let the user configure, held as one immutable value.
 * [com.neverlate.ui.tasks.TasksViewModel] exposes a single [kotlinx.coroutines.flow.StateFlow] of
 * this type and updates it with `.copy(...)` on each user intent — the same "one state, `.copy()`
 * to change a slice of it" pattern the rest of this codebase already uses for UI state.
 *
 * The **text query** used to live here too (feature 03b), but feature 04b pulled it out into its
 * own `StateFlow` (see [com.neverlate.ui.tasks.TasksViewModel]'s `query`): sort/group/priority
 * filter stay immediate, while the query alone goes through a `debounce`. [shapedBy] below takes
 * the query as a separate parameter for that reason.
 *
 * Defaults match "as if the user had touched nothing yet": soonest-deadline-first, ungrouped, no
 * priority filter — the closest in-memory equivalent to the pre-feature-03b list.
 */
data class TaskListCriteria(
    val sortField: TaskSortField = TaskSortField.Deadline,
    val direction: SortDirection = SortDirection.Ascending,
    val groupAxis: TaskGroupAxis = TaskGroupAxis.None,
    /** Empty set = no filtering, i.e. show every priority (D5). */
    val priorityFilter: Set<Priority> = emptySet(),
)

/**
 * US-1: keeps only the tasks whose [com.neverlate.data.tasks.Task.title] contains [query] as a
 * substring, ignoring case (`"pres"` matches "Preparar Presentación"). A blank [query] — the
 * field's initial and cleared state — means "no filter": `this` is returned unchanged rather than
 * filtered against an empty string (every title technically "contains" `""`, but returning the
 * same list instance avoids allocating an identical copy for the common case).
 */
fun List<TaskUiModel>.filteredBy(query: String): List<TaskUiModel> =
    if (query.isBlank()) this else filter { it.task.title.contains(query, ignoreCase = true) }

/**
 * D5 (`priority-sorting`): keeps only tasks whose [com.neverlate.data.tasks.Task.priority] is in
 * [priorities]. An **empty** set means "no filter" (the pre-feature/default behaviour) — matching
 * [filteredBy]'s own "blank means no filter" rule for the text query, so the two filters compose
 * with the same mental model. [priorities] is a [Set], not a single nullable value, because the
 * filter is **multi-select** (US-2): any subset — including [Priority.NONE], so "show me my
 * un-triaged backlog" is expressible too — can be active at once.
 */
fun List<TaskUiModel>.filteredByPriority(priorities: Set<Priority>): List<TaskUiModel> =
    if (priorities.isEmpty()) this else filter { it.task.priority in priorities }

/**
 * US-2: sorts by [field], in [direction]. Every branch below is itself an exhaustive `when` over
 * [direction], so if a third [TaskSortField] or a third [SortDirection] is ever added, this stops
 * compiling until it too is handled; the mistake is caught at build time, not discovered later at
 * runtime.
 *
 * Sorting by [TaskSortField.Deadline] compares a **nullable** key —
 * [com.neverlate.data.tasks.Task.deadline] is `Long?`, since a duration-only task has no deadline
 * at all. [compareBy] paired with `nullsLast()` asks the standard library to place a null deadline
 * last, without ever unwrapping it: the alternative — `deadline!!` plus an invented sentinel value
 * to sort by — is exactly the kind of runtime crash null-safety exists to make unnecessary.
 *
 * The spec requires a null deadline to sort last **in either direction** (US-2), so descending
 * order is *not* obtained by reversing the whole ascending comparator — `comparator.reversed()`
 * would flip the null-vs-non-null comparison too, moving null deadlines to the *front* instead.
 * `nullsLast(reverseOrder())` reverses only the ordering *among non-null* deadlines, leaving
 * `nullsLast`'s own "null sorts last" behavior untouched.
 *
 * [TaskSortField.Priority] (D3, `priority-sorting`) compares [Priority.rank] — never
 * [Enum.ordinal] (see [Priority]'s KDoc) — and **inverts** the numeric direction: ascending means
 * "most important first" (`HIGH` → `NONE`), consistent with "ascending = the ones to deal with
 * first are on top" already meaning "soonest deadline"/"A→Z" for the other two fields. Making
 * ascending mean `NONE`-first would be numerically tidy and behaviourally backwards, so
 * [SortDirection.Ascending] is wired to [compareByDescending] here on purpose.
 *
 * Feature 04c: a completed task ([com.neverlate.data.tasks.Task.completedAt] non-null) always
 * sorts **after** every pending one, regardless of [field]/[direction] — done work is no longer
 * something to act on, so it drops to the bottom rather than competing for a spot by deadline,
 * title, or priority. `compareBy { completed }` (false < true) is prepended as the *primary* key.
 *
 * D3 also fixes the **full tiebreak stack**, always applied in this order after the primary field:
 * (1) completed-last, (2) the selected field, (3) deadline ascending (nulls last), (4) title A→Z
 * case-insensitive. Step 4 guarantees a **total order** — two tasks with the same field value, the
 * same deadline (or both null) and the same title (case-insensitively) is the only case left
 * unresolved, at which point they are genuinely interchangeable — so the list never reshuffles
 * between two renders of identical data (`Modifier.animateItem()` relies on that stability).
 * Priority is **not** part of this tiebreak stack for `Deadline`/`Title` sorts: it only affects
 * order when it is itself the selected [field] (or the selected group axis) — see D3's "two
 * deliberate non-decisions".
 */
fun List<TaskUiModel>.sortedBy(field: TaskSortField, direction: SortDirection): List<TaskUiModel> {
    val fieldComparator: Comparator<TaskUiModel> = when (field) {
        TaskSortField.Deadline -> when (direction) {
            SortDirection.Ascending -> compareBy(nullsLast()) { it.task.deadline }
            SortDirection.Descending -> compareBy(nullsLast(reverseOrder())) { it.task.deadline }
        }
        TaskSortField.Title -> when (direction) {
            SortDirection.Ascending -> compareBy { it.task.title.lowercase() }
            SortDirection.Descending -> compareByDescending { it.task.title.lowercase() }
        }
        TaskSortField.Priority -> when (direction) {
            // Ascending = most important first: HIGH (rank 3) sorts before NONE (rank 0), the
            // opposite of rank's own numeric order — see the KDoc above for why.
            SortDirection.Ascending -> compareByDescending { it.task.priority.rank }
            SortDirection.Descending -> compareBy { it.task.priority.rank }
        }
    }

    val deadlineTiebreak: Comparator<TaskUiModel> = compareBy(nullsLast()) { it.task.deadline }
    val titleTiebreak: Comparator<TaskUiModel> = compareBy { it.task.title.lowercase() }

    val completedLast = compareBy<TaskUiModel> { it.task.completedAt != null }
        .then(fieldComparator)
        .then(deadlineTiebreak)
        .then(titleTiebreak)
    return sortedWith(completedLast)
}

/**
 * US-3: groups by [UrgencyLevel], **reusing** [urgencyLevelFor] instead of recomputing urgency a
 * second way — the "extend, don't duplicate" rule this project already applies to domain logic.
 *
 * Feature 04c: a completed task is never "urgent" (its countdown/urgency color is not even shown —
 * see [com.neverlate.ui.tasks.TaskRow]), so it is bucketed as [UrgencyLevel.Calm] here regardless
 * of its actual `remainingMillis`/`isTimedOut` — reusing the calmest existing section instead of
 * inventing a dedicated "completed" one (out of scope for this feature). [sortedBy]'s
 * completed-last ordering then sinks it below any genuinely calm *pending* tasks within that
 * section.
 *
 * Returns [TaskSection]s in [URGENCY_DISPLAY_ORDER], dropping empty sections — the same shape
 * [groupedByPriority] returns for its own axis, so [shapedBy] can treat both axes identically.
 */
fun List<TaskUiModel>.groupedByUrgency(): List<TaskSection> {
    val byLevel = groupBy { uiModel ->
        if (uiModel.task.completedAt != null) UrgencyLevel.Calm else urgencyLevelFor(uiModel.remainingMillis, uiModel.isTimedOut)
    }
    return URGENCY_DISPLAY_ORDER.mapNotNull { level ->
        byLevel[level]?.let { tasksInSection -> TaskSection(TaskGroupKey.ByUrgency(level), tasksInSection) }
    }
}

/**
 * D6 (`priority-sorting`): groups by [com.neverlate.data.tasks.Task.priority]. Unlike
 * [groupedByUrgency], a **completed** task groups under its **real** priority here, not a
 * catch-all: priority stays true after completion (a finished HIGH task is still a HIGH task),
 * whereas urgency stops being meaningful the moment a task is done (its countdown is not even
 * shown). [sortedBy]'s completed-last key still sinks a completed task below the pending ones
 * within its section.
 *
 * Returns [TaskSection]s in [PRIORITY_DISPLAY_ORDER] (Alta → Media → Baja → Sin prioridad),
 * dropping empty sections — the same shape [groupedByUrgency] returns for its own axis.
 */
fun List<TaskUiModel>.groupedByPriority(): List<TaskSection> {
    val byPriority = groupBy { it.task.priority }
    return PRIORITY_DISPLAY_ORDER.mapNotNull { priority ->
        byPriority[priority]?.let { tasksInSection -> TaskSection(TaskGroupKey.ByPriority(priority), tasksInSection) }
    }
}

/**
 * Display order for urgency sections: most urgent first. This is the **opposite** of
 * [UrgencyLevel]'s declaration order (`Calm, Soon, Urgent, Overdue`), which is ordered that way for
 * the threshold comparisons inside [urgencyLevelFor], not for display — a small reminder that an
 * enum's declaration order is a language detail the code needs for its own reasons, not
 * automatically the order a UI should render it in.
 */
private val URGENCY_DISPLAY_ORDER = listOf(
    UrgencyLevel.Overdue,
    UrgencyLevel.Urgent,
    UrgencyLevel.Soon,
    UrgencyLevel.Calm,
)

/**
 * Display order for priority sections (US-3): Alta → Media → Baja → Sin prioridad — most important
 * first, the same "what matters most leads" convention [URGENCY_DISPLAY_ORDER] uses for its own
 * axis. This is also the **opposite** of [Priority]'s declaration order (`NONE, LOW, MEDIUM, HIGH`)
 * for the same reason: declaration order serves [Priority.rank]'s comparisons, not display.
 */
private val PRIORITY_DISPLAY_ORDER = listOf(
    Priority.HIGH,
    Priority.MEDIUM,
    Priority.LOW,
    Priority.NONE,
)

/**
 * D4 (`priority-sorting`): the section key a [TaskSection] groups under — one axis-agnostic type
 * instead of [ShapedTaskList.Grouped] carrying a `Map<UrgencyLevel, ...>` that could never grow a
 * second axis without duplicating the whole grouped rendering path. See D4 in
 * `docs/specs/2026-08-17-priority-sorting.md` for the full "generalize vs. duplicate" reasoning.
 */
sealed interface TaskGroupKey {
    data class ByUrgency(val level: UrgencyLevel) : TaskGroupKey
    data class ByPriority(val priority: Priority) : TaskGroupKey
}

/** One section of a [ShapedTaskList.Grouped] result: a [key] (which axis value this section is)
 *  paired with the (already sorted) tasks that fall under it. */
data class TaskSection(val key: TaskGroupKey, val tasks: List<TaskUiModel>)

/**
 * The shaped result the Tasks screen renders: either one flat list, or that same list split into
 * non-empty sections along whichever axis [TaskListCriteria.groupAxis] selects (US-3's grouping
 * chips). Modeled as a `sealed interface` — not a single class with a nullable "sections" field —
 * so that every place that renders a [ShapedTaskList] (see [com.neverlate.ui.tasks.TasksScreen])
 * gets its own exhaustive `when` too, the same benefit [sortedBy] above gets from [TaskSortField].
 *
 * [Grouped] carries a [List] of [TaskSection] (D4), in fixed display order, rather than a
 * `Map<UrgencyLevel, ...>` — a `List` makes "these are in display order" a property of the type
 * itself, and the same shape now serves both the urgency and the priority axis without a second
 * `Grouped`-like variant (see D4's full reasoning).
 */
sealed interface ShapedTaskList {
    data class Flat(val tasks: List<TaskUiModel>) : ShapedTaskList
    data class Grouped(val sections: List<TaskSection>) : ShapedTaskList
}

/**
 * True when there is nothing to render. This is the signal
 * [com.neverlate.ui.tasks.TasksViewModel] uses to tell "filtered/grouped down to zero visible
 * tasks" (`TasksUiState.NoResults`) apart from "there are no tasks at all"
 * (`TasksUiState.Empty`) — two different reasons for an empty screen that deserve two different
 * messages (US-4).
 */
fun ShapedTaskList.isEmpty(): Boolean = when (this) {
    is ShapedTaskList.Flat -> tasks.isEmpty()
    // shapedBy already drops empty sections below, so an empty list here really does mean zero
    // tasks survived filtering — not "every section happened to come up empty".
    is ShapedTaskList.Grouped -> sections.isEmpty()
}

/**
 * The full pipeline: filter by text (US-1) then by priority (D5, US-2) first, then either a flat
 * sort (US-2) or a group-then-sort-within-each-section (US-3) — sorting always happens *inside* a
 * section when grouping is active, exactly as the spec requires, never across the whole list
 * first.
 *
 * `with(criteria) { ... }` reads `groupAxis`, `sortField`, `direction`, and `priorityFilter` off
 * one receiver instead of repeating `criteria.` four times. It earns its place here — as opposed
 * to `let`/`run`, which would fit just as well syntactically — because the block genuinely
 * performs *several* reads of the *same* object; a scope function chosen only "because one is
 * expected" would be no clearer than not using one at all.
 *
 * [query] arrives as its own parameter (feature 04b), separate from [criteria], since it is the
 * one input [com.neverlate.ui.tasks.TasksViewModel] debounces before this function ever sees it —
 * sort/group/priority-filter criteria stay immediate.
 */
fun List<TaskUiModel>.shapedBy(query: String, criteria: TaskListCriteria): ShapedTaskList {
    val filtered = filteredBy(query).filteredByPriority(criteria.priorityFilter)

    return with(criteria) {
        when (groupAxis) {
            TaskGroupAxis.None -> ShapedTaskList.Flat(filtered.sortedBy(sortField, direction))
            TaskGroupAxis.Urgency -> ShapedTaskList.Grouped(
                filtered.groupedByUrgency().sortedWithinSections(sortField, direction),
            )
            TaskGroupAxis.Priority -> ShapedTaskList.Grouped(
                filtered.groupedByPriority().sortedWithinSections(sortField, direction),
            )
        }
    }
}

/** Sorts the tasks *inside* each already-built [TaskSection], preserving section order/identity —
 *  the shared last step [shapedBy] uses for both grouping axes. */
private fun List<TaskSection>.sortedWithinSections(
    field: TaskSortField,
    direction: SortDirection,
): List<TaskSection> = map { section -> section.copy(tasks = section.tasks.sortedBy(field, direction)) }
