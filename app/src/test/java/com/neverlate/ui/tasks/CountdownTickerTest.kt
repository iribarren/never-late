package com.neverlate.ui.tasks

import com.neverlate.data.tasks.Task
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [tickIntervalFor] (`reduce-motion` spec, D4 — the clamp rationale, and the
 * "Testing" section's required cases). Pure Kotlin, no Android dependency, so these run on the
 * JVM with no fakes or dispatcher scaffolding needed.
 */
class CountdownTickerTest {

    private val now = 1_000_000L

    private fun runningTask(id: Long, timerEndsAt: Long) =
        Task(id = id, title = "running-$id", timerEndsAt = timerEndsAt)

    private fun pausedTask(id: Long, remainingMillis: Long? = null) =
        Task(id = id, title = "paused-$id", remainingMillis = remainingMillis)

    // reduceMotion = false --------------------------------------------------------------------

    @Test
    fun `reduceMotion false with an empty task list returns the full-motion interval`() {
        assertEquals(TICK_INTERVAL_MILLIS, tickIntervalFor(reduceMotion = false, tasks = emptyList(), now = now))
    }

    @Test
    fun `reduceMotion false with an imminently-expiring running task still returns the full-motion interval`() {
        // Even a task about to expire this instant must not change the cadence when reduceMotion
        // is off - the clamp only exists to serve the reduced-motion branch.
        val tasks = listOf(runningTask(id = 1, timerEndsAt = now + 5))

        assertEquals(TICK_INTERVAL_MILLIS, tickIntervalFor(reduceMotion = false, tasks = tasks, now = now))
    }

    // reduceMotion = true, nothing running soon ------------------------------------------------

    @Test
    fun `reduceMotion true with an empty task list returns the reduced-motion interval`() {
        assertEquals(
            REDUCED_MOTION_TICK_INTERVAL_MILLIS,
            tickIntervalFor(reduceMotion = true, tasks = emptyList(), now = now),
        )
    }

    @Test
    fun `reduceMotion true with only paused tasks returns the reduced-motion interval`() {
        // No running task at all (all timerEndsAt are null) - nothing to clamp against.
        val tasks = listOf(pausedTask(id = 1), pausedTask(id = 2, remainingMillis = 42_000L))

        assertEquals(
            REDUCED_MOTION_TICK_INTERVAL_MILLIS,
            tickIntervalFor(reduceMotion = true, tasks = tasks, now = now),
        )
    }

    // reduceMotion = true, the clamp engaging ---------------------------------------------------

    @Test
    fun `reduceMotion true clamps down to the soonest running task's expiry`() {
        val tasks = listOf(runningTask(id = 1, timerEndsAt = now + 12_000L))

        assertEquals(12_000L, tickIntervalFor(reduceMotion = true, tasks = tasks, now = now))
    }

    @Test
    fun `reduceMotion true with a running task already past its expiry floors at the full-motion interval, never zero or negative`() {
        // The busy-loop guard (D4): an expired-but-still-running task must never produce a
        // delay(0) (or negative) cadence.
        val tasks = listOf(runningTask(id = 1, timerEndsAt = now - 30_000L))

        assertEquals(TICK_INTERVAL_MILLIS, tickIntervalFor(reduceMotion = true, tasks = tasks, now = now))
    }

    @Test
    fun `reduceMotion true with a running task expiring exactly now floors at the full-motion interval`() {
        val tasks = listOf(runningTask(id = 1, timerEndsAt = now))

        assertEquals(TICK_INTERVAL_MILLIS, tickIntervalFor(reduceMotion = true, tasks = tasks, now = now))
    }

    @Test
    fun `reduceMotion true only considers running tasks' expiry, ignoring paused ones in the mix`() {
        // A paused task carries no timerEndsAt at all, so it cannot be mistaken for the soonest
        // expiry even though it sorts first in the list.
        val tasks = listOf(
            pausedTask(id = 1, remainingMillis = 200L),
            runningTask(id = 2, timerEndsAt = now + 12_000L),
            runningTask(id = 3, timerEndsAt = now + 500_000L),
        )

        assertEquals(12_000L, tickIntervalFor(reduceMotion = true, tasks = tasks, now = now))
    }
}
