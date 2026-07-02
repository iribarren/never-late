package com.neverlate.ui.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Unique name for the periodic refresh job — see [WidgetRefreshWorker.enqueuePeriodic]. */
private const val UNIQUE_WORK_NAME = "widget_refresh"

/**
 * The lowest interval WorkManager allows for a `PeriodicWorkRequest`. Below this it silently
 * clamps up to it, so a widget's remaining-time readout can go up to ~15 minutes stale between
 * refreshes even while sitting idle. That is expected, not a bug — see the feature spec's US-5:
 * widgets do not (and cannot) count down second by second, unlike the app's own countdown ticker
 * (`countdownTicker` in `ui/tasks/CountdownTicker.kt`).
 */
private const val REFRESH_INTERVAL_MINUTES = 15L

/**
 * Periodically redraws [PendingTasksWidget] so its remaining-time readout stays roughly current
 * even when nobody touches a task (no create/edit/delete/start/pause happens to trigger
 * [WidgetRefreshingTaskRepository]'s change-driven refresh).
 *
 * `CoroutineWorker` is WorkManager's suspend-friendly base class: `doWork` runs off the main
 * thread and can call suspend functions directly, which [PendingTasksWidget.updateAll] is.
 */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        PendingTasksWidget().updateAll(applicationContext)
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
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
                REFRESH_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
