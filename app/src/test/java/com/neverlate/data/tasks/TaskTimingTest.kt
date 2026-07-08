package com.neverlate.data.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

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

    // durationParts -----------------------------------------------------------------------------
    // Pure split into (hours, minutes); the localized "1 h 30 min" label is assembled in the UI
    // layer from string resources, so it is exercised by TasksScreen's Compose tests, not here.

    @Test
    fun `durationParts splits into whole hours and leftover minutes`() {
        assertEquals(1L to 30L, durationParts(90 * 60_000L))
    }

    @Test
    fun `durationParts returns zero minutes for a whole number of hours`() {
        assertEquals(2L to 0L, durationParts(120 * 60_000L))
    }

    @Test
    fun `durationParts returns zero hours for a sub-hour duration`() {
        assertEquals(0L to 45L, durationParts(45 * 60_000L))
    }

    @Test
    fun `durationParts returns zero, zero for a zero duration`() {
        assertEquals(0L to 0L, durationParts(0L))
    }

    // formatDeadlineForInput / parseDeadline -----------------------------------------------------

    @Test
    fun `parseDeadline and formatDeadlineForInput round-trip a valid date-time string`() {
        val text = "24/12/2026 20:30"

        val millis = parseDeadline(text)

        assertTrue("expected \"$text\" to parse successfully", millis != null)
        assertEquals(text, formatDeadlineForInput(millis!!))
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

    @Test
    fun `parseDeadline reads the fixed pattern regardless of the default locale`() {
        // The input round-trip is pinned to Locale.ROOT, so switching the JVM default locale must
        // not change how a typed deadline parses — that determinism is the whole point of keeping
        // input parsing separate from locale-aware display.
        val text = "24/12/2026 20:30"
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar")) // a locale with very different formats
            assertEquals(parseDeadline(text), parseDeadline(text))
            assertTrue(parseDeadline(text) != null)
        } finally {
            Locale.setDefault(previous)
        }
    }

    // formatDeadlineForDisplay -------------------------------------------------------------------

    @Test
    fun `formatDeadlineForDisplay follows the requested locale`() {
        val millis = parseDeadline("24/12/2026 20:30")!!

        val english = formatDeadlineForDisplay(millis, Locale.US)
        val spanish = formatDeadlineForDisplay(millis, Locale.forLanguageTag("es-ES"))

        // We avoid asserting an exact string (it depends on the JDK's CLDR data), but the two
        // locales must render the same instant differently — e.g. month/day order and 12h vs 24h.
        assertTrue("expected a non-empty display string", english.isNotBlank())
        assertNotEquals(english, spanish)
    }

    // deadlineFromPickedDateTime -----------------------------------------------------------------
    // US-4: DatePickerState.selectedDateMillis is UTC midnight of the picked day, not a local-zone
    // instant. These tests pin an explicit non-UTC ZoneId (never ZoneId.systemDefault()) so the
    // off-by-one-day guard is verified deterministically regardless of the machine running the test.

    /**
     * Mimics exactly what `DatePickerState.selectedDateMillis` produces for a picked calendar day:
     * UTC midnight of that day.
     */
    private fun utcMidnightMillisFor(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun `deadlineFromPickedDateTime resolves to the picked calendar day in a negative-offset zone (US-4)`() {
        val zone = ZoneId.of("America/New_York") // UTC-5 / UTC-4
        val pickedDateMillis = utcMidnightMillisFor(2026, 7, 10)

        val result = deadlineFromPickedDateTime(pickedDateMillis, hour = 14, minute = 30, zone = zone)

        val resolved = Instant.ofEpochMilli(result).atZone(zone)
        assertEquals(LocalDate.of(2026, 7, 10), resolved.toLocalDate())
        assertEquals(14, resolved.hour)
        assertEquals(30, resolved.minute)
    }

    @Test
    fun `deadlineFromPickedDateTime resolves to the picked calendar day in a positive-offset zone`() {
        val zone = ZoneId.of("Asia/Tokyo") // UTC+9
        val pickedDateMillis = utcMidnightMillisFor(2026, 7, 10)

        val result = deadlineFromPickedDateTime(pickedDateMillis, hour = 14, minute = 30, zone = zone)

        val resolved = Instant.ofEpochMilli(result).atZone(zone)
        assertEquals(LocalDate.of(2026, 7, 10), resolved.toLocalDate())
        assertEquals(14, resolved.hour)
        assertEquals(30, resolved.minute)
    }

    @Test
    fun `deadlineFromPickedDateTime at 00_00 does not roll back to the previous day`() {
        val zone = ZoneId.of("America/New_York")
        val pickedDateMillis = utcMidnightMillisFor(2026, 7, 10)

        val result = deadlineFromPickedDateTime(pickedDateMillis, hour = 0, minute = 0, zone = zone)

        val resolved = Instant.ofEpochMilli(result).atZone(zone)
        assertEquals(LocalDate.of(2026, 7, 10), resolved.toLocalDate())
        assertEquals(0, resolved.hour)
        assertEquals(0, resolved.minute)
    }

    @Test
    fun `deadlineFromPickedDateTime at 23_59 does not roll forward to the next day`() {
        val zone = ZoneId.of("Asia/Tokyo")
        val pickedDateMillis = utcMidnightMillisFor(2026, 7, 10)

        val result = deadlineFromPickedDateTime(pickedDateMillis, hour = 23, minute = 59, zone = zone)

        val resolved = Instant.ofEpochMilli(result).atZone(zone)
        assertEquals(LocalDate.of(2026, 7, 10), resolved.toLocalDate())
        assertEquals(23, resolved.hour)
        assertEquals(59, resolved.minute)
    }

    @Test
    fun `deadlineFromPickedDateTime round-trips through formatDeadlineForInput and parseDeadline (US-5)`() {
        // formatDeadlineForInput/parseDeadline are pinned to ZoneId.systemDefault(), so this
        // round-trip uses the default zone consistently on both ends (rather than an explicit
        // non-UTC zone) to stay deterministic: parseDeadline(formatDeadlineForInput(x)) == x holds
        // by construction regardless of which zone systemDefault() happens to be.
        val pickedDateMillis = utcMidnightMillisFor(2026, 12, 24)

        val picked = deadlineFromPickedDateTime(pickedDateMillis, hour = 20, minute = 30)
        val text = formatDeadlineForInput(picked)
        val parsedBack = parseDeadline(text)

        assertTrue("expected the picked value to parse back cleanly", parsedBack != null)
        assertEquals(picked, parsedBack)
    }
}
