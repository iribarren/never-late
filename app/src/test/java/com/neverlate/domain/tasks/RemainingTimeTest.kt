package com.neverlate.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests for [remainingTimeFor] — the classifier that carries the feature spec's Format
 * matrix (`docs/specs/2026-08-16-compact-remaining-time.md`). Every row of that table, plus every
 * boundary the spec locks down (59s/60s, the hour boundary, the 24h boundary, the zero-hour-part
 * days case, and the floor-not-round truncation rule), gets its own test here so the branching is
 * proven independently of any `Context`/`Locale`/string-resource concern — see
 * [com.neverlate.ui.components.formatRemainingLabel] for where that presentation layer is tested
 * instead.
 */
class RemainingTimeTest {

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
    }

    // TimeUp ------------------------------------------------------------------------------------

    @Test
    fun `exact zero classifies as TimeUp`() {
        assertEquals(RemainingTime.TimeUp, remainingTimeFor(0L))
    }

    // UnderMinute / the 59s-60s truncation boundary ----------------------------------------------

    @Test
    fun `one second classifies as UnderMinute`() {
        assertEquals(RemainingTime.UnderMinute, remainingTimeFor(1_000L))
    }

    @Test
    fun `fifty nine seconds still classifies as UnderMinute`() {
        assertEquals(RemainingTime.UnderMinute, remainingTimeFor(59_000L))
    }

    @Test
    fun `exactly sixty seconds crosses into Minutes(1), not UnderMinute`() {
        assertEquals(RemainingTime.Minutes(1), remainingTimeFor(60_000L))
    }

    // Minutes-only --------------------------------------------------------------------------------

    @Test
    fun `minutes-only remaining time classifies as Minutes`() {
        assertEquals(RemainingTime.Minutes(38), remainingTimeFor(38 * MINUTE))
    }

    @Test
    fun `fifty nine minutes classifies as Minutes, just under the hour boundary`() {
        assertEquals(RemainingTime.Minutes(59), remainingTimeFor(59 * MINUTE))
    }

    @Test
    fun `minutes are floored, not rounded, within the minutes-only tier`() {
        // 38m 59s must still read as 38m, never rounding up to 39m.
        assertEquals(RemainingTime.Minutes(38), remainingTimeFor(38 * MINUTE + 59_000L))
    }

    // Exact hours -----------------------------------------------------------------------------------

    @Test
    fun `exact hours with no leftover minutes classify as Hours`() {
        assertEquals(RemainingTime.Hours(2), remainingTimeFor(2 * HOUR))
    }

    @Test
    fun `truncation floors to the minute, so 2h 0m 59s still classifies as Hours(2)`() {
        // Locked decision: floor, never round — the label must not jump to HoursMinutes(2, 1) nor
        // report a phantom minute just because 59s have elapsed into the next one.
        assertEquals(RemainingTime.Hours(2), remainingTimeFor(2 * HOUR + 59_000L))
    }

    // Hours + minutes ---------------------------------------------------------------------------

    @Test
    fun `hours and minutes both present classify as HoursMinutes`() {
        assertEquals(RemainingTime.HoursMinutes(2, 38), remainingTimeFor(2 * HOUR + 38 * MINUTE))
    }

    @Test
    fun `23h59m classifies as HoursMinutes, just under the 24h boundary`() {
        assertEquals(RemainingTime.HoursMinutes(23, 59), remainingTimeFor(23 * HOUR + 59 * MINUTE))
    }

    // The 24h boundary into the days tier ----------------------------------------------------------

    @Test
    fun `exactly 24h crosses into DaysHoursMinutes(1, 0, 0)`() {
        assertEquals(RemainingTime.DaysHoursMinutes(1, 0, 0), remainingTimeFor(DAY))
    }

    @Test
    fun `36h10m classifies as DaysHoursMinutes(1, 12, 10)`() {
        assertEquals(
            RemainingTime.DaysHoursMinutes(1, 12, 10),
            remainingTimeFor(36 * HOUR + 10 * MINUTE),
        )
    }

    @Test
    fun `exact two days classifies as DaysHoursMinutes(2, 0, 0)`() {
        assertEquals(RemainingTime.DaysHoursMinutes(2, 0, 0), remainingTimeFor(2 * DAY))
    }

    @Test
    fun `a day plus minutes with a zero hour part still carries all three parts`() {
        // 1d 0h 30m: the days tier never drops the zero hour part the way the sub-day tiers drop
        // a zero minute part (contrast Hours(2), not HoursMinutes(2, 0)).
        assertEquals(
            RemainingTime.DaysHoursMinutes(1, 0, 30),
            remainingTimeFor(DAY + 30 * MINUTE),
        )
    }

    @Test
    fun `truncation floors within the days tier too`() {
        // 2d 0h 0m holds for the first 59 seconds past the exact-day boundary, same floor rule as
        // the sub-day tiers.
        assertEquals(RemainingTime.DaysHoursMinutes(2, 0, 0), remainingTimeFor(2 * DAY + 59_000L))
    }
}
