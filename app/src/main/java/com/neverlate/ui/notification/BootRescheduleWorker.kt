package com.neverlate.ui.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.neverlate.data.DataStoreUserPreferencesRepository
import com.neverlate.data.tasks.NeverLateDatabase
import com.neverlate.data.tasks.RoomTaskRepository
import com.neverlate.domain.tasks.minutesToMillis
import com.neverlate.domain.tasks.remindersToSchedule
import com.neverlate.domain.tasks.timeUpAlertsToSchedule
import kotlinx.coroutines.flow.first

/**
 * Reprograms every still-future alarm of **both** [ReminderKind]s (US-2, US-4 of the times-up-alert
 * feature). Two triggers enqueue this same worker (D10):
 *
 * - [BootReceiver], right after `BOOT_COMPLETED` — a device restart wipes every `AlarmManager`
 *   alarm, so every future alarm must be reprogrammed from scratch.
 * - [com.neverlate.NeverLateApplication.onCreate], on every cold start — otherwise installing the
 *   update that ships the times-up-alert feature schedules no `TIME_UP` alarms at all until each
 *   task is next edited or the phone reboots (the same hole a force-stop or an app-standby-bucket
 *   demotion can open, both of which can also clear alarms).
 *
 * This is exactly the deferrable, non-time-critical background work `WorkManager` is meant for —
 * unlike the reminder alarm itself ([AlarmManagerReminderScheduler]), nobody needs this to run at a
 * precise instant, only "sometime shortly after boot/cold-start", which is the same trade-off
 * [com.neverlate.ui.widget.TaskSurfacesRefreshWorker] already makes for its own periodic refresh.
 * It is idempotent (scheduling replaces a task's alarm rather than stacking one, via
 * `FLAG_UPDATE_CURRENT` plus a stable request code) and past-due alarms are simply dropped by
 * [remindersToSchedule]/[timeUpAlertsToSchedule]'s future-check (D7) — safe to enqueue on every
 * cold start, not just the first one after install.
 *
 * Like [ReminderReceiver], this worker has no Activity-scoped repositories to reuse, so it
 * reconstructs equivalent ones directly from the process-wide singletons ([NeverLateDatabase],
 * a fresh [DataStoreUserPreferencesRepository]).
 */
class BootRescheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val preferences = DataStoreUserPreferencesRepository(applicationContext).userPreferences.first()
        // US-4: reminders switched off means nothing should be (re)scheduled, boot or not.
        if (!preferences.remindersEnabled) return Result.success()

        val database = NeverLateDatabase.getInstance(applicationContext)
        val tasks = RoomTaskRepository(database.taskDao()).observeTasks().first()
        val leadMillis = minutesToMillis(preferences.reminderLeadMinutes)
        val scheduler = AlarmManagerReminderScheduler(applicationContext)
        val now = System.currentTimeMillis()

        // The actual decision — which tasks still deserve a reminder, and when — is the same pure
        // functions the reminder-scheduling decorator relies on for a single task; here they are
        // applied to every task at once, one per ReminderKind.
        remindersToSchedule(tasks, now = now, leadMillis = leadMillis)
            .forEach { plan -> scheduler.schedule(plan.taskId, ReminderKind.LEAD_TIME, plan.triggerAtMillis) }
        timeUpAlertsToSchedule(tasks, now = now)
            .forEach { plan -> scheduler.schedule(plan.taskId, ReminderKind.TIME_UP, plan.triggerAtMillis) }

        return Result.success()
    }

    companion object {
        /** Enqueues this worker once; [BootReceiver] and [com.neverlate.NeverLateApplication] both
         *  call this — see the class KDoc for why there are two triggers. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<BootRescheduleWorker>().build())
        }
    }
}
