package com.neverlate.data.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTimingTest {

    // computeRemainingMillis ------------------------------------------------------------------

    @Test
    fun `never started task with only a duration counts down that duration`() {
        val task = Task(title = "Leer", estimatedDurationMillis = 20 * 60_000L)
        val now = 1_000_000L

        assertEquals(20 * 60_000L, computeRemainingMillis(task, now))
    }

    @Test
    fun `never started task with only a deadline counts down to that deadline`() {
        val now = 1_000_000L
        val task = Task(title = "Entregar informe", deadline = now + 5 * 60_000L)

        assertEquals(5 * 60_000L, computeRemainingMillis(task, now))
    }

    @Test
    fun `when both duration and deadline are set, the countdown targets the deadline (US-5)`() {
        val now = 1_000_000L
        val task = Task(
            title = "Entregar informe",
            estimatedDurationMillis = 60 * 60_000L, // 1 hour, purely informational per US-5
            deadline = now + 5 * 60_000L,
        )

        assertEquals(5 * 60_000L, computeRemainingMillis(task, now))
    }

    @Test
    fun `running task derives remaining time from timerEndsAt, ignoring duration and deadline`() {
        val now = 1_000_000L
        val task = Task(
            title = "En curso",
            estimatedDurationMillis = 60 * 60_000L,
            deadline = now + 999 * 60_000L,
            timerEndsAt = now + 90_000L,
        )

        assertEquals(90_000L, computeRemainingMillis(task, now))
    }

    @Test
    fun `paused task uses the frozen remainingMillis regardless of now`() {
        val task = Task(title = "Pausada", remainingMillis = 42_000L)

        assertEquals(42_000L, computeRemainingMillis(task, now = 999_999_999L))
    }

    @Test
    fun `remaining time never goes negative once the deadline has passed`() {
        val now = 1_000_000L
        val task = Task(title = "Vencida", deadline = now - 60_000L)

        assertEquals(0L, computeRemainingMillis(task, now))
    }

    @Test
    fun `running task past its end instant clamps to zero instead of a negative value`() {
        val now = 1_000_000L
        val task = Task(title = "Vencida en curso", timerEndsAt = now - 1_000L)

        assertEquals(0L, computeRemainingMillis(task, now))
    }

    @Test
    fun `task with neither duration nor deadline and never started has zero remaining`() {
        val task = Task(title = "Sin tiempo definido")

        assertEquals(0L, computeRemainingMillis(task, now = 1_000_000L))
    }

    // formatRemaining ---------------------------------------------------------------------------

    @Test
    fun `formatRemaining renders zero as 00_00`() {
        assertEquals("00:00", formatRemaining(0L))
    }

    @Test
    fun `formatRemaining renders under an hour as mm_ss`() {
        assertEquals("00:05", formatRemaining(5_000L))
        assertEquals("01:05", formatRemaining(65_000L))
        assertEquals("59:59", formatRemaining(59 * 60_000L + 59_000L))
    }

    @Test
    fun `formatRemaining switches to h_mm_ss at exactly one hour`() {
        assertEquals("1:00:00", formatRemaining(3_600_000L))
    }

    @Test
    fun `formatRemaining renders more than an hour as h_mm_ss`() {
        assertEquals("1:01:01", formatRemaining(3_600_000L + 61_000L))
    }

    // formatDurationLabel -----------------------------------------------------------------------

    @Test
    fun `formatDurationLabel renders whole hours and minutes together`() {
        assertEquals("1 h 30 min", formatDurationLabel(90 * 60_000L))
    }

    @Test
    fun `formatDurationLabel omits minutes when the duration is a whole number of hours`() {
        assertEquals("2 h", formatDurationLabel(120 * 60_000L))
    }

    @Test
    fun `formatDurationLabel renders sub-hour durations as minutes only`() {
        assertEquals("45 min", formatDurationLabel(45 * 60_000L))
    }

    @Test
    fun `formatDurationLabel renders zero as 0 min`() {
        assertEquals("0 min", formatDurationLabel(0L))
    }

    // formatDeadline / parseDeadline -------------------------------------------------------------

    @Test
    fun `parseDeadline and formatDeadline round-trip a valid date-time string`() {
        val text = "24/12/2026 20:30"

        val millis = parseDeadline(text)

        assertTrue("expected \"$text\" to parse successfully", millis != null)
        assertEquals(text, formatDeadline(millis!!))
    }

    @Test
    fun `parseDeadline rejects text that does not match the expected pattern`() {
        assertNull(parseDeadline("not a date"))
        assertNull(parseDeadline(""))
    }

    @Test
    fun `parseDeadline rejects out-of-range dates instead of rolling them over`() {
        assertNull(parseDeadline("32/13/2026 20:30"))
        assertNull(parseDeadline("24/12/2026 25:30"))
    }
}
