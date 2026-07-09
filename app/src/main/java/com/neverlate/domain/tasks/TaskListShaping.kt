package com.neverlate.domain.tasks

import com.neverlate.ui.tasks.TaskUiModel

/**
 * Pure, Android-free list shaping for the Tasks screen (feature 03b) — filter, sort, and group,
 * all performed **in memory** over the list [com.neverlate.ui.tasks.TasksViewModel] already has
 * loaded (no new Room query, no network involved). This mirrors the same "keep the decision in
 * plain Kotlin, let the platform layer stay a thin shell around it" split [ReminderPlanning.kt]
 * already uses for reminder scheduling — everything below takes plain values and returns plain
 * values, so a JVM test can cover it with no emulator.
 *
 * This file doubles as this project's lesson vehicle for core Kotlin language features the
 * codebase already leans on without ever naming them: null-safety, `when` as an exhaustive
 * expression, higher-order collection functions + `Comparator`, and scope functions. See
 * `tutorial/03b-filtro-orden-memoria.md`.
 */

/** Which field to sort the task list by (US-2). */
enum class TaskSortField { Deadline, Title }

/** Ascending ("soonest deadline first" / "A→Z") or descending ("latest" / "Z→A") direction. */
enum class SortDirection { Ascending, Descending }

/**
 * Everything the Tasks screen's sort/group controls (sort chips, direction toggle, group toggle)
 * let the user configure, held as one immutable value. [com.neverlate.ui.tasks.TasksViewModel]
 * exposes a single [kotlinx.coroutines.flow.StateFlow] of this type and updates it with
 * `.copy(...)` on each user intent — the same "one state, `.copy()` to change a slice of it"
 * pattern the rest of this codebase already uses for UI state.
 *
 * The **text query** used to live here too (feature 03b), but feature 04b pulled it out into its
 * own `StateFlow` (see [com.neverlate.ui.tasks.TasksViewModel]'s `query`): sort/group stay
 * immediate, while the query alone goes through a `debounce`. [shapedBy] below takes the query as
 * a separate parameter for that reason.
 *
 * Defaults match "as if the user had touched nothing yet": soonest-deadline-first, ungrouped — the
 * closest in-memory equivalent to the pre-feature-03b list.
 */
data class TaskListCriteria(
    val sortField: TaskSortField = TaskSortField.Deadline,
    val direction: SortDirection = SortDirection.Ascending,
    val grouped: Boolean = false,
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
 * US-2: sorts by [field], in [direction]. Both branches below are themselves an exhaustive `when`
 * over [direction], so if a third [TaskSortField] or a third [SortDirection] is ever added, this
 * stops compiling until it too is handled; the mistake is caught at build time, not discovered
 * later at runtime.
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
 * Feature 04c: a completed task ([com.neverlate.data.tasks.Task.completedAt] non-null) always
 * sorts **after** every pending one, regardless of [field]/[direction] — done work is no longer
 * something to act on, so it drops to the bottom rather than competing for a spot by deadline or
 * title. `compareBy { completed }` (false < true) is prepended as the *primary* key, with the
 * field/direction comparator only breaking ties within each of the two groups — `.then` runs the
 * second comparator only when the first left two elements equal.
 */
fun List<TaskUiModel>.sortedBy(field: TaskSortField, direction: SortDirection): List<TaskUiModel> {
    val comparator: Comparator<TaskUiModel> = when (field) {
        TaskSortField.Deadline -> when (direction) {
            SortDirection.Ascending -> compareBy(nullsLast()) { it.task.deadline }
            SortDirection.Descending -> compareBy(nullsLast(reverseOrder())) { it.task.deadline }
        }
        TaskSortField.Title -> when (direction) {
            SortDirection.Ascending -> compareBy { it.task.title.lowercase() }
            SortDirection.Descending -> compareByDescending { it.task.title.lowercase() }
        }
    }
    val completedLast = compareBy<TaskUiModel> { it.task.completedAt != null }.then(comparator)
    return sortedWith(completedLast)
}

/**
 * US-3: groups by [UrgencyLevel], **reusing** [urgencyLevelFor] instead of recomputing urgency a
 * second way — the "extend, don't duplicate" rule this project already applies to domain logic.
 * [groupBy] returns entries in **first-seen** order, not [UrgencyLevel]'s own declaration order,
 * which is why [shapedBy] below re-orders the result explicitly before it reaches the screen.
 *
 * Feature 04c: a completed task is never "urgent" (its countdown/urgency color is not even shown —
 * see [com.neverlate.ui.tasks.TaskRow]), so it is bucketed as [UrgencyLevel.Calm] here regardless
 * of its actual `remainingMillis`/`isTimedOut` — reusing the calmest existing section instead of
 * inventing a dedicated "completed" one (out of scope for this feature). [sortedBy]'s
 * completed-last ordering then sinks it below any genuinely calm *pending* tasks within that
 * section.
 */
fun List<TaskUiModel>.groupedByUrgency(): Map<UrgencyLevel, List<TaskUiModel>> =
    groupBy { uiModel ->
        if (uiModel.task.completedAt != null) UrgencyLevel.Calm else urgencyLevelFor(uiModel.remainingMillis, uiModel.isTimedOut)
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
 * The shaped result the Tasks screen renders: either one flat list, or that same list split into
 * non-empty urgency sections (US-3's grouping toggle). Modeled as a `sealed interface` — not a
 * single class with a nullable "sections" field — so that every place that renders a
 * [ShapedTaskList] (see [com.neverlate.ui.tasks.TasksScreen]) gets its own exhaustive `when` too,
 * the same benefit [sortedBy] above gets from `TaskSortField`.
 */
sealed interface ShapedTaskList {
    data class Flat(val tasks: List<TaskUiModel>) : ShapedTaskList
    data class Grouped(val sections: Map<UrgencyLevel, List<TaskUiModel>>) : ShapedTaskList
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
    // shapedBy already drops empty sections below, so an empty map here really does mean zero
    // tasks survived filtering — not "every section happened to come up empty".
    is ShapedTaskList.Grouped -> sections.isEmpty()
}

/**
 * The full pipeline: filter (US-1) first, then either a flat sort (US-2) or a group-then-sort-
 * within-each-section (US-3) — sorting always happens *inside* a section when grouping is active,
 * exactly as the spec requires, never across the whole list first.
 *
 * `with(criteria) { ... }` reads `grouped`, `sortField`, and `direction` off one receiver instead
 * of repeating `criteria.` three times. It earns its place here — as opposed to `let`/`run`, which
 * would fit just as well syntactically — because the block genuinely performs *several* reads of
 * the *same* object; a scope function chosen only "because one is expected" would be no clearer
 * than not using one at all.
 *
 * [query] arrives as its own parameter (feature 04b), separate from [criteria], since it is the
 * one input [com.neverlate.ui.tasks.TasksViewModel] debounces before this function ever sees it —
 * sort/group criteria stay immediate.
 */
fun List<TaskUiModel>.shapedBy(query: String, criteria: TaskListCriteria): ShapedTaskList {
    val filtered = filteredBy(query)

    return with(criteria) {
        if (grouped) {
            val sections = filtered.groupedByUrgency()
                // Destructuring a Map.Entry, the same component1()/component2() mechanism a
                // Pair destructures with — here the key (_) is unused, only the value matters.
                .mapValues { (_, tasksInSection) -> tasksInSection.sortedBy(sortField, direction) }
                .filterValues { it.isNotEmpty() }

            // Rebuild in URGENCY_DISPLAY_ORDER: keep only the sections that survived filtering
            // above (mapNotNull drops the nulls from sections without a match), in the fixed
            // display order rather than groupBy's first-seen order. Iterable<Pair<K, V>>.toMap()
            // preserves that encounter order, unlike building a Map some other way would guarantee.
            val ordered = URGENCY_DISPLAY_ORDER
                .mapNotNull { level -> sections[level]?.let { tasksInSection -> level to tasksInSection } }
                .toMap()
            ShapedTaskList.Grouped(ordered)
        } else {
            ShapedTaskList.Flat(filtered.sortedBy(sortField, direction))
        }
    }
}
