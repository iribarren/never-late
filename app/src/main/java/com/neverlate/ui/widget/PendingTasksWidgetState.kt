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
    val rows = pendingRowsFor(tasks, now)
    if (rows.isEmpty()) return PendingTasksWidgetModel.Empty
    return PendingTasksWidgetModel.Content(rows)
}

/**
 * How many rows fit in a given `widget-adaptive-layout` size bucket — a "how many rows fit here"
 * render decision, distinct from `pendingRowsFor`'s domain rule of what counts as pending in the
 * first place (spec D3). Kept pure and Glance-free, next to [toWidgetModel], for the same reason
 * that function is: unit-testable on the JVM with no widget host.
 *
 * The small bucket takes 2 rows — the header plus 2 compact rows is what fits inside the
 * `SMALL_WIDGET` bucket's 110dp height ([com.neverlate.ui.widget.PendingTasksWidget]). The large
 * bucket keeps every row [toWidgetModel] already produced (already capped to
 * [com.neverlate.domain.tasks.MAX_PENDING_ROWS] — that cap is not repeated here, it stays the one
 * domain-level ceiling).
 */
private const val SMALL_BUCKET_ROW_COUNT = 2

fun rowsForBucket(rows: List<PendingTaskRow>, isLargeBucket: Boolean): List<PendingTaskRow> =
    if (isLargeBucket) rows else rows.take(SMALL_BUCKET_ROW_COUNT)
