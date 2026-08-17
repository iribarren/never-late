package com.neverlate.domain.tasks

import com.neverlate.data.tasks.Task

/**
 * Pure, Android-free scheduling logic for the times-up-alert feature — kept in its own file, next
 * to [ReminderPlanning.kt], so each file reads as one idea (the same split already used by
 * [com.neverlate.domain.tasks.ColorRole] and [RemainingTime.kt]). [ReminderPlan] and
 * [isReminderInFuture] are reused from `ReminderPlanning.kt`, not duplicated — a time-up alert is
 * scheduled the exact same way a lead-time reminder is, only the *instant* is computed differently.
 */

/**
 * The wall-clock instant (epoch millis) at which [task] runs out of time, or `null` if no such
 * instant exists — see the feature spec's D6 table:
 *
 * - Running timer, no deadline: [Task.timerEndsAt].
 * - Running timer *and* a deadline: `min(timerEndsAt, deadline)` (D3) — a paused-then-resumed
 *   task's frozen [Task.remainingMillis] can push `timerEndsAt` past the deadline, and the
 *   deadline is the earlier, truer commitment (the approved 2026-07-02 countdown rule already says
 *   a deadline dominates the estimated duration).
 * - Paused or never started, with a deadline: [Task.deadline] — it arrives whether or not a timer
 *   is running.
 * - Paused or never started, duration-only: `null` — nothing is counting down against the wall
 *   clock, so there is no instant to alarm on.
 * - Completed ([Task.completedAt] non-null) or [Task.deleted]: `null` — never alert about
 *   something already handled.
 *
 * Whether the resulting instant is still in the future is *not* this function's concern — that is
 * [isReminderInFuture]'s job, applied by the caller, exactly as [reminderTimeFor] is already split
 * from it.
 */
fun timeUpInstantFor(task: Task): Long? {
    if (task.completedAt != null || task.deleted) return null

    val timerEndsAt = task.timerEndsAt
    val deadline = task.deadline

    return when {
        timerEndsAt != null && deadline != null -> minOf(timerEndsAt, deadline)
        timerEndsAt != null -> timerEndsAt
        deadline != null -> deadline
        else -> null
    }
}

/**
 * Every time-up alert that should be scheduled right now, given the current [tasks] and the wall
 * clock [now] — the boot/cold-start counterpart of
 * [com.neverlate.ui.notification.ReminderSchedulingRepository]'s per-task rescheduling, used by
 * [com.neverlate.ui.notification.BootRescheduleWorker]. Never retroactive (D7): a resulting instant
 * at or before [now] yields no plan, via the same [isReminderInFuture] the lead-time path uses.
 */
fun timeUpAlertsToSchedule(tasks: List<Task>, now: Long): List<ReminderPlan> =
    tasks.mapNotNull { task ->
        timeUpInstantFor(task)
            ?.takeIf { instant -> isReminderInFuture(instant, now) }
            ?.let { instant -> ReminderPlan(task.id, instant) }
    }
