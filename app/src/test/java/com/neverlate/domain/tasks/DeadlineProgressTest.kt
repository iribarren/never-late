package com.neverlate.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for [deadlineProgressFor] (feature 19, US-1). Same "pure function, no fake clock
 * needed" style as [UrgencyTest] / [ReminderPlanningTest]: every case is just plain numbers in,
 * plain numbers out.
 */
class DeadlineProgressTest {

    private val totalMillis = 60 * 60_000L // 1 hour

    // No meaningful total window -> null, no bar --------------------------------------------------

    @Test
    fun `null totalMillis is null`() {
        assertNull(deadlineProgressFor(remainingMillis = 30 * 60_000L, totalMillis = null, isTimedOut = false))
    }

    @Test
    fun `zero totalMillis is null`() {
        assertNull(deadlineProgressFor(remainingMillis = 30 * 60_000L, totalMillis = 0L, isTimedOut = false))
    }

    @Test
    fun `negative totalMillis is null`() {
        assertNull(deadlineProgressFor(remainingMillis = 30 * 60_000L, totalMillis = -1L, isTimedOut = false))
    }

    @Test
    fun `null totalMillis is null even when isTimedOut is true`() {
        // No usable window at all beats even the overdue signal: there is nothing to show a full
        // bar *of*.
        assertNull(deadlineProgressFor(remainingMillis = 0L, totalMillis = null, isTimedOut = true))
    }

    // Overdue / no time left -> full bar ------------------------------------------------------------

    @Test
    fun `isTimedOut true is full regardless of remaining`() {
        assertEquals(
            1f,
            deadlineProgressFor(remainingMillis = totalMillis * 10, totalMillis = totalMillis, isTimedOut = true),
        )
    }

    @Test
    fun `zero remaining without isTimedOut is still full`() {
        assertEquals(
            1f,
            deadlineProgressFor(remainingMillis = 0L, totalMillis = totalMillis, isTimedOut = false),
        )
    }

    @Test
    fun `negative remaining is full`() {
        assertEquals(
            1f,
            deadlineProgressFor(remainingMillis = -1_000L, totalMillis = totalMillis, isTimedOut = false),
        )
    }

    // Boundary just above zero remaining -> not the exact full clamp ---------------------------------

    @Test
    fun `remaining one millisecond above zero is barely short of full, not the zero-remaining clamp`() {
        // Distinguishes the formula path (remainingMillis > 0) from the early
        // "remainingMillis <= 0 -> 1f" return above: with only 1ms left of a 1-hour window, elapsed
        // is extremely close to full but must not be exactly 1f the way zero/negative remaining is.
        val fraction = deadlineProgressFor(remainingMillis = 1L, totalMillis = totalMillis, isTimedOut = false)
        assertEquals(1f, fraction!!, 0.0001f)
        assert(fraction < 1f) { "Expected strictly < 1f for remaining=1ms of $totalMillis total, got $fraction" }
    }

    // Remaining at or above the total -> empty bar ---------------------------------------------------

    @Test
    fun `remaining equal to total is empty`() {
        assertEquals(
            0f,
            deadlineProgressFor(remainingMillis = totalMillis, totalMillis = totalMillis, isTimedOut = false),
        )
    }

    @Test
    fun `remaining greater than total is clamped to empty`() {
        assertEquals(
            0f,
            deadlineProgressFor(remainingMillis = totalMillis * 2, totalMillis = totalMillis, isTimedOut = false),
        )
    }

    // Normal case -------------------------------------------------------------------------------------

    @Test
    fun `remaining at three quarters of total is one quarter elapsed`() {
        val remaining = (totalMillis * 0.75).toLong()
        assertEquals(
            0.25f,
            deadlineProgressFor(remainingMillis = remaining, totalMillis = totalMillis, isTimedOut = false)!!,
            0.001f,
        )
    }

    @Test
    fun `remaining at one quarter of total is three quarters elapsed`() {
        val remaining = (totalMillis * 0.25).toLong()
        assertEquals(
            0.75f,
            deadlineProgressFor(remainingMillis = remaining, totalMillis = totalMillis, isTimedOut = false)!!,
            0.001f,
        )
    }

    @Test
    fun `remaining just under total is barely elapsed`() {
        val remaining = totalMillis - 1
        val fraction = deadlineProgressFor(remainingMillis = remaining, totalMillis = totalMillis, isTimedOut = false)
        assertEquals(0f, fraction!!, 0.01f)
    }
}
