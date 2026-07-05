package com.neverlate.ui.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.neverlate.data.tasks.NeverLateDatabase
import com.neverlate.data.tasks.RoomTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fired by `AlarmManager` (see [AlarmManagerReminderScheduler]) at the exact instant one task's
 * reminder is due. `android:exported="false"` in the manifest: only this app's own alarm
 * `PendingIntent` can deliver this broadcast, never another app.
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

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                showReminder(context, taskId)
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
     */
    private suspend fun showReminder(context: Context, taskId: Long) {
        // US-5: graceful degradation if POST_NOTIFICATIONS is denied — no crash, simply nothing
        // shown, same check TasksNotificationService already makes before posting anything.
        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!notificationsEnabled) return

        val database = NeverLateDatabase.getInstance(context)
        val task = RoomTaskRepository(database.taskDao()).observeTask(taskId).first() ?: return
        // The deadline may have been removed (or the task deleted) between scheduling and firing;
        // ReminderSchedulingRepository cancels the alarm on both, but a defensive check costs
        // nothing and this receiver has no other way to be sure the alarm was actually cancelled.
        if (task.deadline == null) return

        ReminderNotificationHelper.ensureChannel(context)
        val locale = context.resources.configuration.locales[0]
        val notification = ReminderNotificationHelper.buildNotification(
            context,
            task,
            locale,
            now = System.currentTimeMillis(),
        )
        NotificationManagerCompat.from(context).notify(ReminderNotificationHelper.notificationIdFor(taskId), notification)
    }

    companion object {
        /** Intent extra carrying the [com.neverlate.data.tasks.Task.id] this alarm is for. */
        const val EXTRA_TASK_ID = "com.neverlate.EXTRA_REMINDER_TASK_ID"

        private const val NO_TASK_ID = -1L
    }
}
