package com.neverlate.ui.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.neverlate.data.sync.SyncStatus
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.ui.notification.TasksNotificationService
import kotlinx.coroutines.flow.Flow

/**
 * Decorates [delegate] so that every write also refreshes the two passive, read-only surfaces
 * that display tasks from outside the app's own UI: the home-screen widget (feature 05) and the
 * lock-screen notification (feature 06).
 *
 * [com.neverlate.ui.tasks.TasksViewModel] stays fresh by *observing* [TaskRepository.observeTasks]
 * — a `Flow` that emits again whenever the underlying rows change. Neither the widget nor the
 * notification can do that for free: the widget lives in the launcher's process and only redraws
 * when told to via `GlanceAppWidget.updateAll` (see [PendingTasksWidget.provideGlance]); the
 * notification is rebuilt on demand by a foreground service that must likewise be "poked" (see
 * [TasksNotificationService.refresh]). There is no cross-process `Flow` collection happening
 * anywhere. This one decorator closes that gap for **both** surfaces from a single place: it wraps
 * the real [TaskRepository] (see [com.neverlate.data.tasks.RoomTaskRepository]) without changing it
 * at all, and refreshes both right after each write that could change what they show.
 *
 * Decorating the interface once — rather than having every `ViewModel` call `updateAll`/`refresh`
 * itself, or nesting a second decorator for the notification on top of a widget-only one — keeps
 * this concern in exactly one spot and keeps the feature 04 `ViewModel`s unaware that either a
 * widget or a notification exists at all.
 */
class TaskSurfacesRefreshingRepository(
    private val delegate: TaskRepository,
    private val context: Context,
) : TaskRepository {

    override fun observeTasks(): Flow<List<Task>> = delegate.observeTasks()

    override fun observeTask(id: Long): Flow<Task?> = delegate.observeTask(id)

    override suspend fun saveTask(task: Task): Long {
        val id = delegate.saveTask(task)
        refreshSurfaces()
        return id
    }

    override suspend fun deleteTask(id: Long) {
        delegate.deleteTask(id)
        refreshSurfaces()
    }

    override suspend fun startTimer(id: Long) {
        delegate.startTimer(id)
        refreshSurfaces()
    }

    override suspend fun pauseTimer(id: Long) {
        delegate.pauseTimer(id)
        refreshSurfaces()
    }

    // Additive feature 11 capability (US-7): forwarded to delegate, same reasoning as
    // com.neverlate.ui.notification.ReminderSchedulingRepository's overrides of these two methods
    // — this decorator has no sync concept of its own and must not swallow the call behind the
    // interface's no-op default.
    override suspend fun refreshFromServer() = delegate.refreshFromServer()

    override fun observeSyncStatus(): Flow<SyncStatus> = delegate.observeSyncStatus()

    private suspend fun refreshSurfaces() {
        // updateAll is a no-op (cheap) when the user has never placed the widget, so this never
        // needs to check "is a widget even on the home screen" first.
        PendingTasksWidget().updateAll(context)
        // Re-evaluates the lock-screen notification: the service reads the fresh task snapshot and
        // either updates the ongoing notification or tears itself down if nothing is pending.
        TasksNotificationService.refresh(context)
    }
}
