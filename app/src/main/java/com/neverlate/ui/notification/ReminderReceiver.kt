package com.neverlate.ui.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.neverlate.data.tasks.NeverLateDatabase
import com.neverlate.data.tasks.RoomTaskRepository
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.computeRemainingMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fired by `AlarmManager` (see [AlarmManagerReminderScheduler]) at the exact instant one task's
 * [ReminderKind.LEAD_TIME] or [ReminderKind.TIME_UP] alarm is due — which one is carried by the
 * [Intent]'s `action` (D1), not inferred from extras. `android:exported="false"` in the manifest:
 * only this app's own alarm `PendingIntent` can deliver this broadcast, never another app.
 *
 * A [BroadcastReceiver]'s [onReceive] must return almost instantly and, unlike a `ViewModel` or a
 * `Service`, has no `suspend`-friendly scope of its own — [goAsync] is the platform's escape hatch
 * for exactly this: it keeps the receiver's process alive a little longer (a few seconds) so a
 * short background job (here: one Room read, then posting a notification) can finish before the
 * system is allowed to tear things down again.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID)
        if (taskId == NO_TASK_ID) return
        // Unknown/absent action: defensively drop it. This also makes a stale alarm from a
        // pre-update install (before ReminderKind existed) harmless instead of crashing or
        // guessing which kind it meant (D1).
        val kind = ReminderKind.entries.firstOrNull { it.action == intent.action } ?: return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                showReminder(context, taskId, kind)
            } finally {
                // Tells the system this receiver's background work is done — skipping this would
                // eventually make Android kill the process while still "waiting" on it.
                pendingResult.finish()
            }
        }
    }

    /**
     * Re-reads the task fresh from Room — the same recipe [TasksNotificationService] and
     * [BootRescheduleWorker] use — rather than trusting anything baked into the alarm's [Intent]:
     * this receiver has no Activity-scoped repository to reuse, since it may run with the app's
     * process not even started.
     *
     * D8: an alarm can outlive the reason it was set. On top of scheduling-time cancellation
     * ([ReminderSchedulingRepository], D9), this is the delivery-time safety net — dropped when the
     * task no longer exists, is deleted, is completed, or (for [ReminderKind.TIME_UP] specifically)
     * is stale: [computeRemainingMillis] shows visibly more than a minute left, meaning this alarm
     * belongs to a superseded plan (timer restarted, deadline pushed back).
     */
    private suspend fun showReminder(context: Context, taskId: Long, kind: ReminderKind) {
        // US-5: graceful degradation if POST_NOTIFICATIONS is denied — no crash, simply nothing
        // shown, same check TasksNotificationService already makes before posting anything.
        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!notificationsEnabled) return

        val database = NeverLateDatabase.getInstance(context)
        val task = RoomTaskRepository(database.taskDao()).observeTask(taskId).first() ?: return
        if (task.deleted || task.completedAt != null) return

        val now = System.currentTimeMillis()
        val notification = when (kind) {
            ReminderKind.LEAD_TIME -> {
                // The deadline may have been removed between scheduling and firing;
                // ReminderSchedulingRepository cancels the alarm on both, but a defensive check
                // costs nothing and this receiver has no other way to be sure the alarm was
                // actually cancelled.
                if (task.deadline == null) return
                val locale = context.resources.configuration.locales[0]
                ReminderNotificationHelper.buildNotification(context, task, locale, now = now)
            }
            ReminderKind.TIME_UP -> {
                if (isStale(task, now)) return
                ReminderNotificationHelper.buildTimeUpNotification(context, task)
            }
        }

        ReminderNotificationHelper.ensureChannel(context)
        NotificationManagerCompat.from(context).notify(
            ReminderNotificationHelper.notificationIdFor(taskId, kind),
            notification,
        )
    }

    /** True when [task]'s countdown still has visibly more than a minute left at [now] — this
     *  [ReminderKind.TIME_UP] alarm belongs to a superseded plan (D8). */
    private fun isStale(task: Task, now: Long): Boolean =
        computeRemainingMillis(task, now) > STALE_TOLERANCE_MILLIS

    companion object {
        /** Intent extra carrying the [com.neverlate.data.tasks.Task.id] this alarm is for. */
        const val EXTRA_TASK_ID = "com.neverlate.EXTRA_REMINDER_TASK_ID"

        private const val NO_TASK_ID = -1L

        /** D8's staleness tolerance: one minute of visible remaining time is "definitely stale",
         *  not just slightly late delivery. */
        private const val STALE_TOLERANCE_MILLIS = 60_000L
    }
}
