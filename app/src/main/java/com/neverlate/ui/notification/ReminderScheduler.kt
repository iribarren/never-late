package com.neverlate.ui.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Schedules and cancels the one-shot alarm that eventually fires [ReminderReceiver] for one task.
 *
 * Declared as an interface — same reasoning as [com.neverlate.data.tasks.TaskRepository] — so
 * [ReminderSchedulingRepository] and [com.neverlate.ui.settings.SettingsViewModel] can be
 * unit-tested against an in-memory fake instead of a real [AlarmManager], which needs an Android
 * runtime to even instantiate. All the *decision* of whether/when to call [schedule] still lives
 * in the pure functions in `domain/tasks/ReminderPlanning.kt`; this interface only knows how to
 * carry that decision out.
 */
interface ReminderScheduler {
    /** (Re)schedules task [taskId]'s reminder to fire at [triggerAtMillis] (epoch millis). */
    fun schedule(taskId: Long, triggerAtMillis: Long)

    /** Cancels task [taskId]'s reminder, if one was scheduled. A no-op if there was none. */
    fun cancel(taskId: Long)
}

/**
 * Real [ReminderScheduler], backed by [AlarmManager] (the platform service for "run this at a
 * concrete wall-clock instant", as opposed to WorkManager's "run this sometime, deferrable" — see
 * the feature spec's Overview for why this feature needs the former).
 *
 * OQ-1 (approved): exact when the platform allows it, gracefully degraded to inexact otherwise —
 * this scheduler never crashes and never asks the caller to handle two different code paths.
 */
class AlarmManagerReminderScheduler(private val context: Context) : ReminderScheduler {

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun schedule(taskId: Long, triggerAtMillis: Long) {
        val pendingIntent = buildPendingIntent(taskId)

        // API < 31 needs no special permission for exact alarms at all. API 31+ must ask at
        // runtime — canScheduleExactAlarms() — because SCHEDULE_EXACT_ALARM, even though declared
        // in the manifest, can be revoked by the user or the system after install.
        val canScheduleExact =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

        if (canScheduleExact) {
            // RTC_WAKEUP: wall-clock time (matches Task.deadline's epoch-millis convention) and
            // wakes the device if asleep. AllowWhileIdle is what lets this still fire on time
            // while the device is in Doze.
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            // Graceful degradation (US-5): same alarm, without the exact/Doze-piercing guarantee —
            // it may arrive a little late, never a crash.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    override fun cancel(taskId: Long) {
        alarmManager.cancel(buildPendingIntent(taskId))
    }

    /**
     * A [PendingIntent]'s identity (for `AlarmManager` to replace or cancel the right one) is its
     * target component plus this request code. Deriving the code from [taskId] via
     * [requestCodeFor] — rather than a fixed constant, or an incrementing counter — is what makes
     * [schedule] replace a task's existing alarm (`FLAG_UPDATE_CURRENT`) instead of stacking a
     * second one when a task is edited, and [cancel] target exactly that task's alarm (US-3).
     * `FLAG_IMMUTABLE` is required from API 31 onward for any `PendingIntent` the app is not
     * itself going to fill in later.
     */
    private fun buildPendingIntent(taskId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodeFor(taskId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/**
 * Deterministic `Int` request code derived from a [com.neverlate.data.tasks.Task.id]. Room assigns
 * ids sequentially starting at 1, so this plain narrowing conversion never collides within this
 * app's practical lifetime. Centralised here (rather than inlined at every call site) so
 * [AlarmManagerReminderScheduler]'s `PendingIntent`s and [ReminderNotificationHelper]'s
 * notification ids agree on the exact same "one task, one int" mapping.
 */
fun requestCodeFor(taskId: Long): Int = taskId.toInt()
