package com.neverlate.ui.notification

import com.neverlate.data.tasks.Task
import com.neverlate.domain.tasks.PendingTaskRow
import com.neverlate.domain.tasks.pendingRowsFor

/**
 * Everything [TasksNotificationHelper] needs to build (or cancel) the lock-screen notification,
 * computed once per refresh by [toNotificationModel].
 *
 * Deliberately holds no formatted, user-facing sentences (no "Tienes 3 tareas pendientes"-style
 * text): building those strings needs a `Context` (to read `res/values/strings.xml`), which would
 * pull an Android import into this file. Keeping this a plain-Kotlin model — same reasoning as
 * [com.neverlate.ui.widget.PendingTasksWidgetModel] — is what makes [toNotificationModel] unit
 * testable on the JVM with no notification manager or emulator involved. The Android-facing text
 * assembly happens in [TasksNotificationHelper], which is the thin shell around this model.
 */
sealed interface TasksNotificationModel {
    /** No tasks at all — [TasksNotificationService] cancels/tears down the notification. */
    data object Empty : TasksNotificationModel

    /**
     * At least one task, already sorted and capped for display via [pendingRowsFor].
     *
     * @param rows the rows to render, most-urgent-first, capped — identical rule to the widget.
     * @param totalPendingCount how many tasks are pending in total, *before* the cap — used for
     *   the redacted public-version summary (see [TasksNotificationHelper]), which counts
     *   everything outstanding even though only [rows] are spelled out with titles.
     */
    data class Content(val rows: List<PendingTaskRow>, val totalPendingCount: Int) : TasksNotificationModel {
        init {
            require(rows.isNotEmpty()) { "Content must have at least one row; use Empty otherwise" }
        }

        /** The single most urgent row — always present, since [rows] is never empty here. */
        val mostUrgent: PendingTaskRow get() = rows.first()
    }
}

/**
 * Pure mapping from the repository's [tasks] at instant [now] to what the lock-screen
 * notification shows.
 *
 * Mirrors [com.neverlate.ui.widget.toWidgetModel] on purpose: both features must agree on what
 * "pending" means (see [pendingRowsFor]'s KDoc), so both call the exact same shared helper rather
 * than each keeping their own copy of the sort/cap rule that could quietly drift apart.
 */
fun toNotificationModel(tasks: List<Task>, now: Long): TasksNotificationModel {
    if (tasks.isEmpty()) return TasksNotificationModel.Empty
    return TasksNotificationModel.Content(rows = pendingRowsFor(tasks, now), totalPendingCount = tasks.size)
}
