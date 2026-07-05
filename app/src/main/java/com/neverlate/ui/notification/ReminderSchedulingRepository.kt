package com.neverlate.ui.notification

import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.domain.tasks.isReminderInFuture
import com.neverlate.domain.tasks.minutesToMillis
import com.neverlate.domain.tasks.reminderTimeFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Decorates [delegate] so that every write which can affect a task's reminder also
 * (re)schedules or cancels it through [scheduler] — the reminder counterpart of
 * [com.neverlate.ui.widget.TaskSurfacesRefreshingRepository] (US-3), kept as a **separate**
 * decorator rather than folded into that one so each stays focused on one passive surface: that
 * one refreshes the widget/notification *summary*, this one owns the *alerting* one-shot alarm.
 *
 * The actual scheduling decision is delegated entirely to the pure functions in
 * `domain/tasks/ReminderPlanning.kt` ([reminderTimeFor], [isReminderInFuture]) — this class is a
 * thin shell that reads the current [Task] and the reminder preferences and hands them to those
 * functions, which is what keeps [reschedule] unit-testable against a fake [ReminderScheduler] and
 * a fake [UserPreferencesRepository], no Android runtime required.
 */
class ReminderSchedulingRepository(
    private val delegate: TaskRepository,
    private val scheduler: ReminderScheduler,
    private val preferences: UserPreferencesRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : TaskRepository {

    override fun observeTasks(): Flow<List<Task>> = delegate.observeTasks()

    override fun observeTask(id: Long): Flow<Task?> = delegate.observeTask(id)

    override suspend fun saveTask(task: Task): Long {
        val id = delegate.saveTask(task)
        // task.id is 0 for a brand-new task; the id the delegate just assigned is what the
        // reminder must actually be keyed by.
        reschedule(task.copy(id = id))
        return id
    }

    override suspend fun deleteTask(id: Long) {
        delegate.deleteTask(id)
        scheduler.cancel(id)
    }

    override suspend fun startTimer(id: Long) = delegate.startTimer(id)

    override suspend fun pauseTimer(id: Long) = delegate.pauseTimer(id)

    /**
     * Always cancels the task's previous alarm first, then schedules a new one only if reminders
     * are enabled, [task] still has a deadline, and the resulting instant is still in the future
     * (OQ-6). This single cancel-then-maybe-schedule sequence is what lets **editing** a task
     * (deadline change, or removing it entirely) simply replace or clear its reminder, with no
     * separate "create" vs. "update" code paths and no risk of two alarms for the same task (US-3).
     */
    private suspend fun reschedule(task: Task) {
        scheduler.cancel(task.id)

        val userPreferences = preferences.userPreferences.first()
        if (!userPreferences.remindersEnabled) return

        val leadMillis = minutesToMillis(userPreferences.reminderLeadMinutes)
        val triggerAt = reminderTimeFor(task, leadMillis) ?: return
        if (isReminderInFuture(triggerAt, now())) {
            scheduler.schedule(task.id, triggerAt)
        }
    }
}
