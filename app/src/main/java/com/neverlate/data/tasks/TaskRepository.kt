package com.neverlate.data.tasks

import com.neverlate.data.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Reads and writes tasks.
 *
 * Declared as an interface — same reasoning as [com.neverlate.data.articles.ArticleRepository]
 * and [com.neverlate.data.UserPreferencesRepository] — so [com.neverlate.ui.tasks.TasksViewModel]
 * and [com.neverlate.ui.tasks.TaskEditViewModel] depend only on this contract, never on
 * [RoomTaskRepository] or any Room type directly. This is also the interface the widget
 * (feature 05) and lock-screen notification (feature 06) will depend on to read the same tasks
 * without touching this feature's UI at all — the most important design constraint of this
 * feature (see the spec's "Reutilización por features futuras" section).
 *
 * Reads return [Flow] so every observer (this feature's UI, and later the widget/notification)
 * sees task changes as they happen, instead of having to poll.
 */
interface TaskRepository {
    /** All tasks, in a stable display order. */
    fun observeTasks(): Flow<List<Task>>

    /** The task whose [Task.id] matches [id], or null once no such task exists (e.g. deleted). */
    fun observeTask(id: Long): Flow<Task?>

    /**
     * Inserts [task] if it has never been saved ([Task.id] is 0), otherwise updates its row.
     * Returns the persisted id — the id SQLite just generated on insert, or the same [Task.id] on
     * update — so a caller that only has a brand-new, id-less [Task] can still learn which row it
     * became. [com.neverlate.ui.notification.ReminderSchedulingRepository] (feature 09) needs
     * exactly that: it schedules a task's reminder keyed by id, including for a task that has
     * never been saved before.
     */
    suspend fun saveTask(task: Task): Long

    /** Removes the task with [id], if it exists. */
    suspend fun deleteTask(id: Long)

    /** Starts (or resumes, if paused) the countdown for the task with [id]. */
    suspend fun startTimer(id: Long)

    /** Pauses the countdown for the task with [id], freezing its remaining time. */
    suspend fun pauseTimer(id: Long)

    /**
     * Triggers an on-demand sync (push then pull) against the backend, added **additively** for
     * feature 11 (US-7: the existing five methods above keep their exact pre-feature-11 meaning
     * and signature). [com.neverlate.ui.tasks.TasksViewModel] calls this for pull-to-refresh —
     * this is the only sync-shaped thing it, or any other task-related ViewModel, ever touches;
     * none of them depend on [com.neverlate.data.sync.SyncEngine] or Retrofit directly.
     *
     * Defaults to a no-op: only [com.neverlate.data.sync.OutboxTaskRepository] (the decorator
     * that actually talks to the backend) overrides it. A pass-through decorator
     * ([ReminderSchedulingRepository][com.neverlate.ui.notification.ReminderSchedulingRepository],
     * [TaskSurfacesRefreshingRepository][com.neverlate.ui.widget.TaskSurfacesRefreshingRepository])
     * must still forward the call to its delegate — see each class's override — or this default
     * would silently swallow it instead of reaching the real implementation further down the chain.
     */
    suspend fun refreshFromServer() {}

    /**
     * The current best-effort sync status, for the minimal sync indicator (OQ-1: a pull-to-refresh
     * spinner plus a subtle offline/syncing hint, not a rich per-task badge). Defaults to
     * [SyncStatus.Idle] for implementations with no sync concept — same reasoning as
     * [refreshFromServer].
     */
    fun observeSyncStatus(): Flow<SyncStatus> = flowOf(SyncStatus.Idle)
}
