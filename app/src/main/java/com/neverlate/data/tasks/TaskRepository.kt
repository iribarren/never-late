package com.neverlate.data.tasks

import kotlinx.coroutines.flow.Flow

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
}
