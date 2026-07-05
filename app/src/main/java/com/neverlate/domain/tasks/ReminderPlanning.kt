package com.neverlate.domain.tasks

import com.neverlate.data.tasks.Task

/**
 * Pure, Android-free scheduling logic for feature 09 (task reminders) — the same "keep the
 * decision in plain Kotlin, let the platform classes stay thin shells around it" split already
 * used by [pendingRowsFor] above. Everything here takes plain values (a [Task], a lead time, a
 * wall-clock instant) and returns a plain value, so [com.neverlate.ui.notification.AlarmManagerReminderScheduler],
 * [com.neverlate.ui.notification.ReminderReceiver] and [com.neverlate.ui.notification.BootRescheduleWorker]
 * never need to be unit-tested to cover *this* logic — a plain JVM test does it, no emulator
 * required.
 */

/** How many milliseconds one minute is — used to turn the Settings screen's lead time (minutes,
 *  the unit a person thinks in) into the millisecond arithmetic [reminderTimeFor] needs. */
private const val MILLIS_PER_MINUTE = 60_000L

/** Converts a lead time expressed in whole minutes (as persisted in `user_prefs`) to milliseconds. */
fun minutesToMillis(minutes: Int): Long = minutes * MILLIS_PER_MINUTE

/**
 * The wall-clock instant (epoch millis) at which [task]'s reminder should fire: [leadMillis]
 * before its [Task.deadline]. Returns null when [task] has no deadline at all — a duration-only
 * task never gets a reminder in this MVP (see the feature spec's Out of Scope).
 */
fun reminderTimeFor(task: Task, leadMillis: Long): Long? =
    task.deadline?.let { deadline -> deadline - leadMillis }

/**
 * OQ-6 (approved): a reminder is only worth scheduling if [reminderAtMillis] still lies strictly
 * in the future relative to [now]. Scheduling one that has already passed would either fire the
 * instant it is set (surprising) or never fire at all depending on the OS — neither is "N minutes
 * before the deadline" once that instant has already gone by, so callers must simply skip it.
 */
fun isReminderInFuture(reminderAtMillis: Long, now: Long): Boolean = reminderAtMillis > now

/** One reminder to (re)schedule: which task, and the wall-clock instant its alarm should fire at. */
data class ReminderPlan(val taskId: Long, val triggerAtMillis: Long)

/**
 * Every reminder that should be scheduled right now, given the current [tasks], the wall clock
 * [now] and the single global [leadMillis] (OQ-4: one lead time, no per-task override) — tasks
 * with a deadline whose [reminderTimeFor] is still in the future ([isReminderInFuture]), everyone
 * else dropped. Used by [com.neverlate.ui.notification.BootRescheduleWorker] after a device
 * restart wipes every `AlarmManager` alarm (US-2).
 */
fun remindersToSchedule(tasks: List<Task>, now: Long, leadMillis: Long): List<ReminderPlan> =
    tasks.mapNotNull { task ->
        reminderTimeFor(task, leadMillis)
            ?.takeIf { reminderAt -> isReminderInFuture(reminderAt, now) }
            ?.let { reminderAt -> ReminderPlan(task.id, reminderAt) }
    }
