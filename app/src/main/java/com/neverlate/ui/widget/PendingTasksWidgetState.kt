package com.neverlate.ui.widget

import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.computeRemainingMillis
import com.neverlate.data.tasks.formatRemaining

/**
 * How many rows [PendingTasksWidget] draws before it stops, most-urgent-first (see
 * [toWidgetModel]). A home-screen widget has little room and no scrolling gesture worth the
 * complexity here — feature 04's app screen is still the place to see everything.
 */
private const val MAX_VISIBLE_TASKS = 5

/** One rendered row of the widget: a task's title paired with its already-formatted countdown. */
data class PendingTaskRow(val title: String, val remaining: String, val isTimedOut: Boolean)

/** Everything [PendingTasksWidget] needs to draw itself, computed once per redraw. */
sealed interface PendingTasksWidgetModel {
    /** No tasks at all — shown as a friendly empty-state message, never a blank box. */
    data object Empty : PendingTasksWidgetModel

    /** At least one task, already sorted and capped for display. */
    data class Content(val rows: List<PendingTaskRow>) : PendingTasksWidgetModel
}

/**
 * Pure mapping from the repository's [tasks] at instant [now] to what the widget draws.
 *
 * This is the widget's equivalent of [com.neverlate.ui.tasks.TasksViewModel.onTasksTick]: all the
 * decisions that matter (what counts as "pending", the display order, the row cap, the empty
 * state) live here, in plain Kotlin with no Glance/Android imports, precisely so they can be unit
 * tested on the JVM without spinning up a widget host. [PendingTasksWidget] itself stays a thin
 * shell that calls this function and renders its result.
 *
 * "Pending" definition for this feature (the spec left it open, see US-2): every task counts,
 * including ones whose countdown has already reached zero — a widget's job is to show what is
 * outstanding, and quietly dropping timed-out tasks could hide the exact thing the user most
 * needs to notice. Rows are sorted **most-urgent-first** (smallest remaining time first) so the
 * thing closest to running out is always the first line, then capped to [MAX_VISIBLE_TASKS].
 */
fun toWidgetModel(tasks: List<Task>, now: Long): PendingTasksWidgetModel {
    if (tasks.isEmpty()) return PendingTasksWidgetModel.Empty

    val rows = tasks
        .map { task -> task to computeRemainingMillis(task, now) }
        .sortedBy { (_, remainingMillis) -> remainingMillis }
        .take(MAX_VISIBLE_TASKS)
        .map { (task, remainingMillis) ->
            PendingTaskRow(
                title = task.title,
                remaining = formatRemaining(remainingMillis),
                isTimedOut = remainingMillis == 0L,
            )
        }
    return PendingTasksWidgetModel.Content(rows)
}
