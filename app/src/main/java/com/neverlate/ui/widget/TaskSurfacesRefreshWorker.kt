package com.neverlate.ui.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.neverlate.ui.notification.TasksNotificationService
import java.util.concurrent.TimeUnit

/** Unique name for the periodic refresh job — see [TaskSurfacesRefreshWorker.enqueuePeriodic]. */
private const val UNIQUE_WORK_NAME = "task_surfaces_refresh"

/**
 * The lowest interval WorkManager allows for a `PeriodicWorkRequest`. Below this it silently
 * clamps up to it, so a surface's remaining-time readout can go up to ~15 minutes stale between
 * refreshes even while sitting idle. That is expected, not a bug — see the feature spec's US-5:
 * the widget and the lock-screen notification do not (and cannot) count down second by second,
 * unlike the app's own countdown ticker (`countdownTicker` in `ui/tasks/CountdownTicker.kt`).
 */
private const val REFRESH_INTERVAL_MINUTES = 15L

/**
 * Periodically refreshes both read-only task surfaces — the home-screen widget (feature 05) and
 * the lock-screen notification (feature 06) — so their remaining-time readouts stay roughly
 * current even when nobody touches a task (no create/edit/delete/start/pause happens to trigger
 * [TaskSurfacesRefreshingRepository]'s change-driven refresh). Without this, remaining time would
 * only ever move on the wall clock at the next write, and a purely deadline-driven countdown could
 * sit visibly stale.
 *
 * `CoroutineWorker` is WorkManager's suspend-friendly base class: `doWork` runs off the main
 * thread and can call suspend functions directly, which [PendingTasksWidget.updateAll] is. The
 * notification is refreshed by poking its foreground service, which re-reads the tasks and either
 * updates or tears itself down.
 */
class TaskSurfacesRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        PendingTasksWidget().updateAll(applicationContext)
        TasksNotificationService.refresh(applicationContext)
        return Result.success()
    }

    companion object {
        /**
         * Schedules the periodic refresh, once, for the whole app process. `enqueueUniquePeriodicWork`
         * with [ExistingPeriodicWorkPolicy.KEEP] makes this idempotent: calling it again on every
         * app start (see `MainActivity.onCreate`) does not stack duplicate periodic jobs — if one
         * is already scheduled, WorkManager leaves it running untouched.
         */
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<TaskSurfacesRefreshWorker>(
                REFRESH_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
