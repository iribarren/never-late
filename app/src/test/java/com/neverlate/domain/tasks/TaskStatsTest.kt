package com.neverlate.domain.tasks

import com.neverlate.data.tasks.Task
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for [weeklyStatsFor] (feature 04c). Every stat is a plain function of ([tasks],
 * [now], [zone]) — unlike most of this app's clock-driven code, this suite never needs a fake
 * [java.time.Clock] or an emulator: [now] and [zone] are just constructor arguments, arranged once
 * per test the same way any other input value would be (see [weeklyStatsFor]'s own KDoc for why
 * that split matters). [zone] is deliberately a non-UTC zone — the same convention
 * `TaskTimingTest` uses — so a bug that silently assumed UTC would show up as a failing test here
 * too, not just in a specific timezone in production.
 */
class TaskStatsTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")

    /** Builds an epoch-millis instant from a local date/time in [zone], so every fixture below
     *  reads as a plain calendar date instead of an opaque millisecond number. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    // Arrange, once for the whole suite: `now` is deliberately mid-week and mid-day, so weekStart
    // and weekEnd below are both clearly different instants from `now` itself.
    // Wednesday 2024-01-10, 15:30 local.
    private val now = at(2024, 1, 10, 15, 30)

    // The ISO week `now` falls in: Monday 2024-01-08 00:00 (inclusive) through Monday 2024-01-15
    // 00:00 (exclusive), both local to `zone`.
    private val weekStart = at(2024, 1, 8)
    private val weekEnd = at(2024, 1, 15)

    /** Builds a [Task] with only the fields a given test cares about. */
    private fun task(
        title: String = "Task",
        deadline: Long? = null,
        completedAt: Long? = null,
        deleted: Boolean = false,
    ) = Task(title = title, deadline = deadline, completedAt = completedAt, deleted = deleted)

    // Empty input -------------------------------------------------------------------------------

    @Test
    fun `empty task list produces all-zero stats with an undefined on-time ratio`() {
        // Act:
        val stats = weeklyStatsFor(emptyList(), now, zone)

        // Assert: nothing to count, and no dated completion at all to compute a ratio from.
        assertEquals(WeeklyTaskStats(completedThisWeek = 0, onTimePercent = null, dueSoon = 0), stats)
    }

    // All pending ---------------------------------------------------------------------------------

    @Test
    fun `tasks with no completedAt count toward neither completedThisWeek nor the on-time ratio`() {
        val tasks = listOf(task(completedAt = null), task(completedAt = null))

        val stats = weeklyStatsFor(tasks, now, zone)

        assertEquals(0, stats.completedThisWeek)
        assertNull(stats.onTimePercent)
    }

    // Deleted exclusion -----------------------------------------------------------------------------

    @Test
    fun `deleted tasks are excluded from completedThisWeek, onTimePercent, and dueSoon alike`() {
        // Would otherwise count toward completedThisWeek (and on-time) if not for `deleted`.
        val deletedCompletion = task(completedAt = weekStart + 1_000L, deadline = weekStart + 2_000L, deleted = true)
        // Would otherwise count toward dueSoon if not for `deleted`.
        val deletedDueSoon = task(deadline = now + 1_000L, deleted = true)

        val stats = weeklyStatsFor(listOf(deletedCompletion, deletedDueSoon), now, zone)

        assertEquals(WeeklyTaskStats(completedThisWeek = 0, onTimePercent = null, dueSoon = 0), stats)
    }

    // On time vs. late -----------------------------------------------------------------------------

    @Test
    fun `completed on time and completed late both count toward completedThisWeek, but only on-time toward the ratio`() {
        val onTime = task(completedAt = weekStart + 1_000L, deadline = weekStart + 2_000L) // before its deadline
        val late = task(completedAt = weekStart + 3_000L, deadline = weekStart + 2_000L) // after its deadline

        val stats = weeklyStatsFor(listOf(onTime, late), now, zone)

        assertEquals(2, stats.completedThisWeek)
        assertEquals(50, stats.onTimePercent)
    }

    // Completed, no deadline ------------------------------------------------------------------------

    @Test
    fun `a completion with no deadline counts toward completedThisWeek but is excluded from the on-time ratio`() {
        val noDeadline = task(completedAt = weekStart + 1_000L, deadline = null)
        // completedAt == deadline is still "on time" (the comparison is <=, not <).
        val onTimeDated = task(completedAt = weekStart + 2_000L, deadline = weekStart + 2_000L)

        val stats = weeklyStatsFor(listOf(noDeadline, onTimeDated), now, zone)

        assertEquals(2, stats.completedThisWeek)
        // Only the one dated completion enters the ratio, and it was on time.
        assertEquals(100, stats.onTimePercent)
    }

    // Week boundaries -------------------------------------------------------------------------------

    @Test
    fun `completedAt exactly at weekStart counts toward this week`() {
        val atWeekStart = task(completedAt = weekStart)

        val stats = weeklyStatsFor(listOf(atWeekStart), now, zone)

        assertEquals(1, stats.completedThisWeek)
    }

    @Test
    fun `completedAt exactly at weekEnd belongs to next week, not this one`() {
        val atWeekEnd = task(completedAt = weekEnd)

        val stats = weeklyStatsFor(listOf(atWeekEnd), now, zone)

        assertEquals(0, stats.completedThisWeek)
    }

    // Due-soon boundary -----------------------------------------------------------------------------

    @Test
    fun `a deadline exactly at now is not due soon`() {
        val dueAtNow = task(deadline = now)

        val stats = weeklyStatsFor(listOf(dueAtNow), now, zone)

        assertEquals(0, stats.dueSoon)
    }

    @Test
    fun `a deadline just after now is due soon`() {
        val dueJustAfterNow = task(deadline = now + 1)

        val stats = weeklyStatsFor(listOf(dueJustAfterNow), now, zone)

        assertEquals(1, stats.dueSoon)
    }

    @Test
    fun `a deadline exactly at the due-soon horizon (now + 24h) is still due soon`() {
        val atHorizon = task(deadline = now + DUE_SOON_MILLIS)

        val stats = weeklyStatsFor(listOf(atHorizon), now, zone)

        assertEquals(1, stats.dueSoon)
    }

    @Test
    fun `a deadline just past the due-soon horizon is not due soon`() {
        val pastHorizon = task(deadline = now + DUE_SOON_MILLIS + 1)

        val stats = weeklyStatsFor(listOf(pastHorizon), now, zone)

        assertEquals(0, stats.dueSoon)
    }

    @Test
    fun `a completed task is never due soon, even with a deadline inside the horizon`() {
        val completedButDueSoonDeadline = task(completedAt = now, deadline = now + 1)

        val stats = weeklyStatsFor(listOf(completedButDueSoonDeadline), now, zone)

        assertEquals(0, stats.dueSoon)
    }

    // onTimePercent rounding + null ------------------------------------------------------------------

    @Test
    fun `onTimePercent rounds to the nearest int - 1 of 3 on time rounds to 33`() {
        val onTime = task(completedAt = weekStart + 1_000L, deadline = weekStart + 2_000L)
        val lateOne = task(completedAt = weekStart + 3_000L, deadline = weekStart + 2_000L)
        val lateTwo = task(completedAt = weekStart + 4_000L, deadline = weekStart + 2_000L)

        val stats = weeklyStatsFor(listOf(onTime, lateOne, lateTwo), now, zone)

        assertEquals(3, stats.completedThisWeek)
        assertEquals(33, stats.onTimePercent)
    }

    @Test
    fun `onTimePercent is null when no completed-this-week task has a deadline`() {
        val noDeadlineA = task(completedAt = weekStart + 1_000L, deadline = null)
        val noDeadlineB = task(completedAt = weekStart + 2_000L, deadline = null)

        val stats = weeklyStatsFor(listOf(noDeadlineA, noDeadlineB), now, zone)

        assertEquals(2, stats.completedThisWeek)
        assertNull(stats.onTimePercent)
    }
}
