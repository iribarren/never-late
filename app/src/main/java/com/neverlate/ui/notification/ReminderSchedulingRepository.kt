package com.neverlate.ui.notification

import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.sync.SyncStatus
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.domain.tasks.isReminderInFuture
import com.neverlate.domain.tasks.minutesToMillis
import com.neverlate.domain.tasks.reminderTimeFor
import com.neverlate.domain.tasks.timeUpInstantFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Decorates [delegate] so that every write which can affect a task's reminders also
 * (re)schedules or cancels both its [ReminderKind.LEAD_TIME] and [ReminderKind.TIME_UP] alarms
 * through [scheduler] — the reminder counterpart of
 * [com.neverlate.ui.widget.TaskSurfacesRefreshingRepository] (US-3), kept as a **separate**
 * decorator rather than folded into that one so each stays focused on one passive surface: that
 * one refreshes the widget/notification *summary*, this one owns the *alerting* one-shot alarms.
 *
 * The actual scheduling decision is delegated entirely to the pure functions in
 * `domain/tasks/ReminderPlanning.kt` ([reminderTimeFor], [isReminderInFuture]) and
 * `domain/tasks/TimeUpPlanning.kt` ([timeUpInstantFor]) — this class is a thin shell that reads
 * the current [Task] and the reminder preferences and hands them to those functions, which is
 * what keeps [rescheduleLeadTime]/[rescheduleTimeUp] unit-testable against a fake
 * [ReminderScheduler] and a fake [UserPreferencesRepository], no Android runtime required.
 *
 * [startTimer] and [pauseTimer] are no longer pass-throughs (times-up-alert feature, §4): both
 * move [Task.timerEndsAt], the [ReminderKind.TIME_UP] anchor, so both must reschedule it — this is
 * "the easy-to-forget part of the feature: it has no UI surface at all" (spec's own words). Nothing
 * on screen changes when it is wrong; the alert simply never fires, or fires about a paused timer.
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
        val saved = task.copy(id = id)
        rescheduleLeadTime(saved)
        rescheduleTimeUp(saved)
        return id
    }

    override suspend fun deleteTask(id: Long) {
        delegate.deleteTask(id)
        scheduler.cancel(id, ReminderKind.LEAD_TIME)
        scheduler.cancel(id, ReminderKind.TIME_UP)
    }

    override suspend fun startTimer(id: Long) {
        delegate.startTimer(id) // writes the new timerEndsAt
        rescheduleTimeUp(id) // must re-read the task: the instant changed underneath us
    }

    override suspend fun pauseTimer(id: Long) {
        delegate.pauseTimer(id)
        rescheduleTimeUp(id) // re-read yields timerEndsAt == null -> cancel only
    }

    // Additive feature 11 capability (US-7): this decorator has no sync concept of its own, so it
    // simply forwards to whichever decorator further down the chain does — see TaskRepository's
    // KDoc for why a pass-through decorator must override these instead of inheriting the default.
    override suspend fun refreshFromServer() = delegate.refreshFromServer()

    override fun observeSyncStatus(): Flow<SyncStatus> = delegate.observeSyncStatus()

    /**
     * Always cancels the task's previous [ReminderKind.LEAD_TIME] alarm first, then schedules a
     * new one only if reminders are enabled, [task] is still warranted (D9:
     * `completedAt == null && !deleted`), still has a deadline, and the resulting instant is still
     * in the future (OQ-6). This single cancel-then-maybe-schedule sequence is what lets **editing**
     * a task (deadline change, or removing it entirely) simply replace or clear its reminder, with
     * no separate "create" vs. "update" code paths and no risk of two alarms for the same task
     * (US-3).
     */
    private suspend fun rescheduleLeadTime(task: Task) {
        scheduler.cancel(task.id, ReminderKind.LEAD_TIME)

        val userPreferences = preferences.userPreferences.first()
        if (!userPreferences.remindersEnabled) return
        if (task.completedAt != null || task.deleted) return

        val leadMillis = minutesToMillis(userPreferences.reminderLeadMinutes)
        val triggerAt = reminderTimeFor(task, leadMillis) ?: return
        if (isReminderInFuture(triggerAt, now())) {
            scheduler.schedule(task.id, ReminderKind.LEAD_TIME, triggerAt)
        }
    }

    /**
     * Same cancel-then-maybe-schedule shape as [rescheduleLeadTime], but for
     * [ReminderKind.TIME_UP]: re-reads [id] fresh from [delegate] (the recipe this class already
     * uses for preferences), rather than trusting a caller-supplied [Task], because [startTimer]
     * and [pauseTimer] only know the id, not the task's new state after the delegate's write.
     */
    private suspend fun rescheduleTimeUp(id: Long) {
        val task = delegate.observeTask(id).first() ?: run {
            scheduler.cancel(id, ReminderKind.TIME_UP)
            return
        }
        rescheduleTimeUp(task)
    }

    private suspend fun rescheduleTimeUp(task: Task) {
        scheduler.cancel(task.id, ReminderKind.TIME_UP)

        val userPreferences = preferences.userPreferences.first()
        if (!userPreferences.remindersEnabled) return
        if (task.completedAt != null || task.deleted) return

        val triggerAt = timeUpInstantFor(task) ?: return
        if (isReminderInFuture(triggerAt, now())) {
            scheduler.schedule(task.id, ReminderKind.TIME_UP, triggerAt)
        }
    }
}
