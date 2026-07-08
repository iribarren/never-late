package com.neverlate.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests for [urgencyLevelFor] (feature 17, US-4). Thresholds per the approved spec
 * (2026-07-08) and [Urgency.kt]'s own comparison operators: `isTimedOut` always wins first, then
 * `remainingMillis <= 5 min` is [UrgencyLevel.Urgent], `<= 60 min` is [UrgencyLevel.Soon],
 * otherwise [UrgencyLevel.Calm] — both threshold comparisons are `<=` (inclusive on the low side),
 * so the boundary value itself falls into the *more urgent* tier and only one millisecond past it
 * falls into the calmer one. Same "pure function, no fake clock needed" style as
 * [ReminderPlanningTest].
 */
class UrgencyTest {

    private val urgentThresholdMillis = 5 * 60_000L
    private val soonThresholdMillis = 60 * 60_000L

    // Overdue -------------------------------------------------------------------------------------

    @Test
    fun `isTimedOut true with zero remaining is Overdue`() {
        assertEquals(UrgencyLevel.Overdue, urgencyLevelFor(remainingMillis = 0L, isTimedOut = true))
    }

    @Test
    fun `isTimedOut true with negative remaining is Overdue`() {
        assertEquals(UrgencyLevel.Overdue, urgencyLevelFor(remainingMillis = -1_000L, isTimedOut = true))
    }

    @Test
    fun `isTimedOut true wins even when remaining would otherwise read as Calm`() {
        // A stale/inconsistent remainingMillis should never override isTimedOut: it is checked
        // first and unconditionally in the implementation.
        assertEquals(
            UrgencyLevel.Overdue,
            urgencyLevelFor(remainingMillis = soonThresholdMillis * 10, isTimedOut = true),
        )
    }

    // Urgent boundary (5 minutes) -------------------------------------------------------------------

    @Test
    fun `remaining just under the urgent threshold is Urgent`() {
        assertEquals(
            UrgencyLevel.Urgent,
            urgencyLevelFor(remainingMillis = urgentThresholdMillis - 1, isTimedOut = false),
        )
    }

    @Test
    fun `remaining exactly at the urgent threshold is Urgent (inclusive boundary)`() {
        assertEquals(
            UrgencyLevel.Urgent,
            urgencyLevelFor(remainingMillis = urgentThresholdMillis, isTimedOut = false),
        )
    }

    @Test
    fun `remaining one millisecond past the urgent threshold is Soon, not Urgent`() {
        assertEquals(
            UrgencyLevel.Soon,
            urgencyLevelFor(remainingMillis = urgentThresholdMillis + 1, isTimedOut = false),
        )
    }

    @Test
    fun `zero remaining without isTimedOut is still Urgent, not Overdue`() {
        // isTimedOut is the only signal for Overdue; a still-ticking task with 0ms left (but not
        // yet flagged as timed out) falls through to the Urgent branch instead.
        assertEquals(UrgencyLevel.Urgent, urgencyLevelFor(remainingMillis = 0L, isTimedOut = false))
    }

    // Soon boundary (60 minutes) --------------------------------------------------------------------

    @Test
    fun `remaining just above the urgent threshold is Soon`() {
        assertEquals(
            UrgencyLevel.Soon,
            urgencyLevelFor(remainingMillis = urgentThresholdMillis + 60_000L, isTimedOut = false),
        )
    }

    @Test
    fun `remaining just under the soon threshold is Soon`() {
        assertEquals(
            UrgencyLevel.Soon,
            urgencyLevelFor(remainingMillis = soonThresholdMillis - 1, isTimedOut = false),
        )
    }

    @Test
    fun `remaining exactly at the soon threshold is Soon (inclusive boundary)`() {
        assertEquals(
            UrgencyLevel.Soon,
            urgencyLevelFor(remainingMillis = soonThresholdMillis, isTimedOut = false),
        )
    }

    // Calm ------------------------------------------------------------------------------------------

    @Test
    fun `remaining one millisecond past the soon threshold is Calm, not Soon`() {
        assertEquals(
            UrgencyLevel.Calm,
            urgencyLevelFor(remainingMillis = soonThresholdMillis + 1, isTimedOut = false),
        )
    }

    @Test
    fun `remaining well past the soon threshold is Calm`() {
        assertEquals(
            UrgencyLevel.Calm,
            urgencyLevelFor(remainingMillis = soonThresholdMillis * 10, isTimedOut = false),
        )
    }
}
