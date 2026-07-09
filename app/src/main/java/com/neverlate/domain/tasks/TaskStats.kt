package com.neverlate.domain.tasks

import com.neverlate.data.tasks.Task
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Pure, Android-free weekly statistics (feature 04c) — the same "keep the decision in plain
 * Kotlin, let the platform layer stay a thin shell around it" split [ReminderPlanning.kt] and
 * [TaskListShaping.kt] already use: [weeklyStatsFor] takes plain values ([Task]s, a wall-clock
 * instant, a time zone) and returns a plain value, so a JVM test can pin every boundary (week
 * edges, the due-soon horizon, on-time ties) with **no fake clock and no emulator** — only
 * [com.neverlate.ui.stats.StatsViewModel] ever reads a real [java.time.Clock]. This is the file
 * the 04c lesson (`tutorial/04c-testing-estadisticas.md`) points at as "why a pure function needs
 * no fake clock": every input already arrived as a plain parameter.
 */

/** The three numbers the Stats screen shows for the current ISO week (US-2). */
data class WeeklyTaskStats(
    /** Tasks marked done ([Task.completedAt] non-null) within the current ISO week. */
    val completedThisWeek: Int,
    /**
     * Of this week's completed tasks that **had** a [Task.deadline], the rounded 0-100 share
     * finished at or before it. `null` when there are no *dated* completions this week to compute
     * a ratio from (never `0`, which would misleadingly read as "always late").
     */
    val onTimePercent: Int?,
    /** Still-pending tasks whose deadline falls within [DUE_SOON_MILLIS] of `now`. */
    val dueSoon: Int,
)

/** The due-soon horizon (US-2's table): a pending task counts as "due soon" within this many
 *  milliseconds of `now` — the boundary value the lesson's boundary test pins. */
const val DUE_SOON_MILLIS: Long = 24 * 60 * 60 * 1000L

/**
 * Computes [WeeklyTaskStats] from [tasks] as of [now] (epoch millis, UTC), using [zone] to resolve
 * the current **ISO week** — Monday 00:00 (local to [zone]) through the following Monday 00:00,
 * exclusive. [now]/[zone] are parameters, never read from a clock in this file (see the file KDoc):
 * the same injected-time discipline `TaskEditViewModel`'s deadline handling already relies on to
 * avoid the timezone/boundary bug class. Deleted tasks ([Task.deleted]) are excluded from every
 * count, matching every other list-facing view of tasks in this app.
 */
fun weeklyStatsFor(tasks: List<Task>, now: Long, zone: ZoneId): WeeklyTaskStats {
    val active = tasks.filterNot { it.deleted }

    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    // previousOrSame(MONDAY) — not previous(MONDAY) — so a `today` that already *is* Monday
    // resolves to itself, not to the Monday a full week earlier.
    val weekStartDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekStart = weekStartDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val weekEnd = weekStartDate.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()

    // "Real completion, not a deadline proxy" (spec Overview): a task counts the week it was
    // *marked done*, regardless of when — or whether — it was due.
    val completedThisWeek = active.filter { task ->
        val completedAt = task.completedAt
        completedAt != null && completedAt >= weekStart && completedAt < weekEnd
    }

    // Only completions that *had* something to be on time for enter the ratio; a completed
    // duration-only task (no deadline) still counts toward completedThisWeek above.
    val datedCompletions = completedThisWeek.filter { it.deadline != null }
    val onTimePercent = if (datedCompletions.isEmpty()) {
        null
    } else {
        val onTimeCount = datedCompletions.count { it.completedAt!! <= it.deadline!! }
        Math.round(onTimeCount * 100.0 / datedCompletions.size).toInt()
    }

    val dueSoon = active.count { task ->
        task.completedAt == null &&
            task.deadline != null &&
            task.deadline > now &&
            task.deadline <= now + DUE_SOON_MILLIS
    }

    return WeeklyTaskStats(
        completedThisWeek = completedThisWeek.size,
        onTimePercent = onTimePercent,
        dueSoon = dueSoon,
    )
}
