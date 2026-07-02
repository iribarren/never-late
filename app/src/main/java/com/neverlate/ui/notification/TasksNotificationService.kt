package com.neverlate.ui.notification

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.neverlate.data.tasks.NeverLateDatabase
import com.neverlate.data.tasks.RoomTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Hosts the ongoing lock-screen notification as a **foreground service** (D2, approved
 * 2026-07-02): a `Service` the system promotes with `startForeground(id, notification)`, which
 * both pins that notification (so it survives even when the app itself is in the background) and
 * tells Android "keep this process alive, it is doing something the user cares about right now".
 *
 * Lifecycle, combined with D1 (notification shown whenever there is >= 1 pending task):
 * - Every write (see [com.neverlate.ui.widget.TaskSurfacesRefreshingRepository]) and every
 *   periodic tick (see [com.neverlate.ui.widget.TaskSurfacesRefreshWorker]) "pokes" this service
 *   via [refresh], which starts it if it is not already running.
 * - [onStartCommand] re-reads the current tasks, recomputes [TasksNotificationModel], and either
 *   promotes/updates the foreground notification ([TasksNotificationModel.Content]) or tears the
 *   service down ([TasksNotificationModel.Empty]) — there is no in-between "running but silent"
 *   state.
 *
 * This service is never bound to (no client needs a live connection to it, unlike, say, a media
 * playback service with transport controls) — [onBind] returns null accordingly.
 */
class TasksNotificationService : Service() {

    // SupervisorJob: a failure in one refresh (e.g. a transient DB hiccup) must not cancel the
    // scope and silently stop future refreshes from working. Dispatchers.Main.immediate keeps
    // calls to startForeground/stopSelf on the main thread, as the Service APIs expect, while the
    // suspend repository read inside still hops off it via Room's own dispatcher.
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch { refreshNotification() }
        // START_NOT_STICKY: if the system kills this process under memory pressure, it should NOT
        // automatically recreate this service with a null intent later. The next real change
        // (a write, or the periodic worker) will poke it again via startForegroundService, which
        // is a better fit here than relying on the system's own restart guess.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    /**
     * Reads a fresh snapshot of tasks — same recipe as [com.neverlate.ui.widget.PendingTasksWidget.provideGlance]:
     * this service cannot reuse `MainActivity`'s manually-injected repository (it may run without
     * the Activity ever starting), so it reaches the same process-wide database singleton
     * directly and builds an equivalent [RoomTaskRepository] on top of it. `.first()` takes a
     * one-shot read rather than an ongoing subscription, matching the "reconstructed on demand"
     * design the feature spec calls for (no cross-process `Flow` collection).
     */
    private suspend fun refreshNotification() {
        TasksNotificationHelper.ensureChannel(applicationContext)

        // API 33+ POST_NOTIFICATIONS (US-4): areNotificationsEnabled() reflects both that runtime
        // permission and any channel/app-level toggle the user has flipped in system settings, on
        // every API level. If it is false there is nothing this service can usefully keep
        // showing, so it degrades gracefully by tearing itself down below instead of staying
        // foregrounded for no visible benefit.
        val notificationsEnabled = NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()

        val database = NeverLateDatabase.getInstance(applicationContext)
        val repository = RoomTaskRepository(database.taskDao())
        val tasks = repository.observeTasks().first()
        val model = toNotificationModel(tasks, System.currentTimeMillis())

        if (model is TasksNotificationModel.Content && notificationsEnabled) {
            // Promotes the service to foreground on the first call, or simply refreshes the
            // already-visible notification's content on every later one — startForeground is safe
            // to call again with the same id while already in the foreground state.
            startForeground(TASKS_NOTIFICATION_ID, TasksNotificationHelper.buildNotification(applicationContext, model))
        } else {
            // Either there are no pending tasks (US-6) or notifications are unavailable — either
            // way, nothing should stay pinned. A service only ever reaches onStartCommand here
            // after being launched via ContextCompat.startForegroundService (see [refresh] below),
            // and the platform requires startForeground to be called within a few seconds of that
            // regardless of the outcome, or the system kills the app
            // (ForegroundServiceDidNotStartInTimeException on API 31+). The placeholder below
            // satisfies that contract — it is posted and torn down again in the same coroutine
            // step, with no suspension in between, so it is never actually rendered to the user —
            // before immediately stopping both the foreground state and the service itself.
            startForeground(TASKS_NOTIFICATION_ID, TasksNotificationHelper.buildEmptyPlaceholder(applicationContext))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    companion object {
        /**
         * Starts (or wakes up) [TasksNotificationService] to re-evaluate the current tasks.
         * [ContextCompat.startForegroundService] is the compat-safe equivalent of
         * `Context.startForegroundService` (API 26+) / `Context.startService` (below it) — the
         * caller never needs its own `SDK_INT` check.
         */
        fun refresh(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TasksNotificationService::class.java))
        }
    }
}
