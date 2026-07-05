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
import kotlinx.coroutines.flow.first

/**
 * Reprograms every still-future reminder after a device restart (US-2), enqueued by
 * [BootReceiver]. This is exactly the deferrable, non-time-critical background work `WorkManager`
 * is meant for — unlike the reminder alarm itself ([AlarmManagerReminderScheduler]), nobody needs
 * this to run at a precise instant, only "sometime shortly after boot", which is the same
 * trade-off [com.neverlate.ui.widget.TaskSurfacesRefreshWorker] already makes for its own periodic
 * refresh.
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

        // The actual decision — which tasks still deserve a reminder, and when — is the same pure
        // function the reminder-scheduling decorator relies on for a single task; here it is
        // applied to every task at once.
        remindersToSchedule(tasks, now = System.currentTimeMillis(), leadMillis = leadMillis)
            .forEach { plan -> scheduler.schedule(plan.taskId, plan.triggerAtMillis) }

        return Result.success()
    }

    companion object {
        /** Enqueues this worker once; [BootReceiver] calls this right after `BOOT_COMPLETED`. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<BootRescheduleWorker>().build())
        }
    }
}
