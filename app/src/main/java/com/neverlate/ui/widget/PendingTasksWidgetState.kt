package com.neverlate.ui.widget

import com.neverlate.data.tasks.Task
import com.neverlate.domain.tasks.pendingRowsFor

/**
 * One rendered row of the widget: a task's title paired with its already-formatted countdown.
 *
 * A `typealias` rather than a fresh `data class`: this is the exact same shape the lock-screen
 * notification (feature 06) needs, so the real definition and the "pending/order/cap" rule that
 * builds it now live in one shared place, [pendingRowsFor] (see its KDoc for why). Keeping the
 * name `PendingTaskRow` resolvable in this package — instead of forcing every call site here to
 * import `com.neverlate.domain.tasks.PendingTaskRow` — avoids an unnecessary rename across the
 * widget's existing code and tests.
 */
typealias PendingTaskRow = com.neverlate.domain.tasks.PendingTaskRow

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
 * This is the widget's equivalent of [com.neverlate.ui.tasks.TasksViewModel.onTasksTick]: the
 * empty-state decision lives here, in plain Kotlin with no Glance/Android imports, precisely so
 * it can be unit tested on the JVM without spinning up a widget host. [PendingTasksWidget] itself
 * stays a thin shell that calls this function and renders its result. The row-level rule (what
 * counts as "pending", the display order, the row cap) is delegated to [pendingRowsFor], shared
 * with the lock-screen notification (feature 06, see [com.neverlate.ui.notification]) so the two
 * surfaces cannot quietly diverge on that rule.
 */
fun toWidgetModel(tasks: List<Task>, now: Long): PendingTasksWidgetModel {
    if (tasks.isEmpty()) return PendingTasksWidgetModel.Empty
    return PendingTasksWidgetModel.Content(pendingRowsFor(tasks, now))
}
