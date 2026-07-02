package com.neverlate.domain.tasks

import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.computeRemainingMillis
import com.neverlate.data.tasks.formatRemaining

/**
 * How many rows a passive, read-only surface (the home-screen widget from feature 05, the
 * lock-screen notification from feature 06) draws before it stops, most-urgent-first — see
 * [pendingRowsFor]. Neither surface has room (or, for the notification, a scrolling gesture)
 * worth showing more than a handful of tasks; the feature 04 app screen is still the place to see
 * everything.
 */
private const val MAX_PENDING_ROWS = 5

/**
 * One "pending task" row: a task's title paired with its already-formatted countdown. This shape
 * is shared verbatim by [com.neverlate.ui.widget.PendingTasksWidgetModel] and
 * [com.neverlate.ui.notification.TasksNotificationModel] — see [pendingRowsFor]'s KDoc for why
 * this lives here instead of in either feature's own package.
 */
data class PendingTaskRow(val title: String, val remaining: String, val isTimedOut: Boolean)

/**
 * Single source of truth for "what counts as pending, in what order, capped to how many" —
 * shared by the pending-tasks widget (feature 05) and the lock-screen notification (feature 06)
 * so the two surfaces can never quietly drift apart on this rule. Both features read the exact
 * same [com.neverlate.data.tasks.TaskRepository] snapshot and must agree on what they show from
 * it; extracting the rule once, here, in plain Kotlin with no Glance/Android imports, is what
 * makes that guarantee possible (and unit-testable on the JVM without a widget host or a
 * notification manager).
 *
 * "Pending" definition (coherent across both features): every task counts, including ones whose
 * countdown has already reached zero — hiding a timed-out task could hide the exact thing the
 * user most needs to notice. Rows are sorted **most-urgent-first** (smallest remaining time
 * first), then capped to [MAX_PENDING_ROWS].
 */
fun pendingRowsFor(tasks: List<Task>, now: Long): List<PendingTaskRow> =
    tasks
        .map { task -> task to computeRemainingMillis(task, now) }
        .sortedBy { (_, remainingMillis) -> remainingMillis }
        .take(MAX_PENDING_ROWS)
        .map { (task, remainingMillis) ->
            PendingTaskRow(
                title = task.title,
                remaining = formatRemaining(remainingMillis),
                isTimedOut = remainingMillis == 0L,
            )
        }
