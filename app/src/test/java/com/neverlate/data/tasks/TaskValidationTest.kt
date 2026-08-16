package com.neverlate.data.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [validateTaskForm] and its private duration parser. Feature "duration-hours-minutes"
 * split the single `durationMinutesText` parameter into [durationHoursText]/[durationMinutesText];
 * every row of that feature's spec's Edge case matrix
 * (`docs/specs/2026-08-16-duration-hours-minutes.md`) has its own test below, named after the row
 * it proves.
 */
class TaskValidationTest {

    @Test
    fun `blank title is invalid even with a valid duration and deadline`() {
        val result = validateTaskForm(
            title = "   ",
            durationHoursText = "",
            durationMinutesText = "30",
            deadlineText = "24/12/2026 20:30",
        )

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.BLANK_TITLE, (result as TaskFormResult.Invalid).error)
    }

    @Test
    fun `malformed deadline text is invalid`() {
        val result = validateTaskForm(title = "Leer", durationHoursText = "", durationMinutesText = "", deadlineText = "not a date")

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.INVALID_DEADLINE_FORMAT, (result as TaskFormResult.Invalid).error)
    }

    @Test
    fun `title is trimmed before being stored`() {
        val result = validateTaskForm(title = "  Leer  ", durationHoursText = "", durationMinutesText = "30", deadlineText = "")

        assertEquals("Leer", (result as TaskFormResult.Valid).title)
    }

    // Edge case matrix ---------------------------------------------------------------------------
    // | Hours | Minutes | Total | Outcome |
    // Each test below is named after (and quotes) its matrix row.

    @Test
    fun `both fields empty with a deadline set is valid with a null duration`() {
        val result = validateTaskForm(
            title = "Entregar",
            durationHoursText = "",
            durationMinutesText = "",
            deadlineText = "24/12/2026 20:30",
        )

        assertTrue(result is TaskFormResult.Valid)
        val valid = result as TaskFormResult.Valid
        assertEquals(null, valid.durationMillis)
        assertEquals(parseDeadline("24/12/2026 20:30"), valid.deadlineMillis)
    }

    @Test
    fun `both fields empty with no deadline is invalid with MISSING_DURATION_OR_DEADLINE`() {
        val result = validateTaskForm(title = "Leer", durationHoursText = "", durationMinutesText = "", deadlineText = "")

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.MISSING_DURATION_OR_DEADLINE, (result as TaskFormResult.Invalid).error)
    }

    @Test
    fun `empty hours and 30 minutes is valid at 30 minutes`() {
        val result = validateTaskForm(title = "Leer", durationHoursText = "", durationMinutesText = "30", deadlineText = "")

        assertTrue(result is TaskFormResult.Valid)
        assertEquals(30 * 60_000L, (result as TaskFormResult.Valid).durationMillis)
    }

    @Test
    fun `2 hours and empty minutes is valid at 120 minutes`() {
        val result = validateTaskForm(title = "Leer", durationHoursText = "2", durationMinutesText = "", deadlineText = "")

        assertTrue(result is TaskFormResult.Valid)
        assertEquals(120 * 60_000L, (result as TaskFormResult.Valid).durationMillis)
    }

    @Test
    fun `2 hours and 30 minutes is valid at 150 minutes`() {
        val result = validateTaskForm(title = "Entregar", durationHoursText = "2", durationMinutesText = "30", deadlineText = "")

        assertTrue(result is TaskFormResult.Valid)
        assertEquals(150 * 60_000L, (result as TaskFormResult.Valid).durationMillis)
    }

    @Test
    fun `0 hours and 90 minutes is valid and normalized to 90 minutes`() {
        // The key locked decision: minutes over 60 is accepted, not rejected — only the total matters.
        val result = validateTaskForm(title = "Leer", durationHoursText = "0", durationMinutesText = "90", deadlineText = "")

        assertTrue(result is TaskFormResult.Valid)
        assertEquals(90 * 60_000L, (result as TaskFormResult.Valid).durationMillis)
    }

    @Test
    fun `0 hours and 0 minutes is invalid, a zero total is not an explicit duration`() {
        val result = validateTaskForm(title = "Leer", durationHoursText = "0", durationMinutesText = "0", deadlineText = "")

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.INVALID_DURATION, (result as TaskFormResult.Invalid).error)
    }

    @Test
    fun `empty hours and 0 minutes is invalid, a zero total`() {
        val result = validateTaskForm(title = "Leer", durationHoursText = "", durationMinutesText = "0", deadlineText = "")

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.INVALID_DURATION, (result as TaskFormResult.Invalid).error)
    }

    @Test
    fun `0 hours and empty minutes is invalid, a zero total`() {
        val result = validateTaskForm(title = "Leer", durationHoursText = "0", durationMinutesText = "", deadlineText = "")

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.INVALID_DURATION, (result as TaskFormResult.Invalid).error)
    }

    @Test
    fun `negative hours is invalid`() {
        val result = validateTaskForm(title = "Leer", durationHoursText = "-1", durationMinutesText = "30", deadlineText = "")

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.INVALID_DURATION, (result as TaskFormResult.Invalid).error)
    }

    @Test
    fun `negative minutes is invalid`() {
        val result = validateTaskForm(title = "Leer", durationHoursText = "2", durationMinutesText = "-1", deadlineText = "")

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.INVALID_DURATION, (result as TaskFormResult.Invalid).error)
    }

    @Test
    fun `non-numeric hours text is invalid`() {
        val result = validateTaskForm(title = "Leer", durationHoursText = "abc", durationMinutesText = "", deadlineText = "")

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.INVALID_DURATION, (result as TaskFormResult.Invalid).error)
    }

    @Test
    fun `non-numeric minutes text is invalid`() {
        val result = validateTaskForm(title = "Leer", durationHoursText = "", durationMinutesText = "abc", deadlineText = "")

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.INVALID_DURATION, (result as TaskFormResult.Invalid).error)
    }

    @Test
    fun `decimal pasted text 1point5 is invalid`() {
        // KeyboardType.Number does not filter pasted text, so "1.5" can reach validation as-is;
        // toLongOrNull() rejects the decimal point.
        val result = validateTaskForm(title = "Leer", durationHoursText = "1.5", durationMinutesText = "", deadlineText = "")

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.INVALID_DURATION, (result as TaskFormResult.Invalid).error)
    }

    @Test
    fun `hours value that overflows Long on hours-to-minutes conversion is invalid, not a crash`() {
        val result = validateTaskForm(
            title = "Leer",
            durationHoursText = Long.MAX_VALUE.toString(),
            durationMinutesText = "",
            deadlineText = "",
        )

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.INVALID_DURATION, (result as TaskFormResult.Invalid).error)
    }

    @Test
    fun `total minutes that overflows Long when converted to millis is invalid, not wrapped`() {
        // 153_722_867_280_913 minutes parses fine as a Long and doesn't overflow hours*60+minutes,
        // but * 60_000L (the millis conversion) does — must be rejected, not silently wrapped.
        val result = validateTaskForm(
            title = "Leer",
            durationHoursText = "",
            durationMinutesText = "153722867280913",
            deadlineText = "",
        )

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.INVALID_DURATION, (result as TaskFormResult.Invalid).error)
    }

    // Combined duration + deadline ----------------------------------------------------------------

    @Test
    fun `valid duration and deadline together produce Valid with both values`() {
        val result = validateTaskForm(
            title = "Entregar",
            durationHoursText = "",
            durationMinutesText = "45",
            deadlineText = "24/12/2026 20:30",
        )

        assertTrue(result is TaskFormResult.Valid)
        val valid = result as TaskFormResult.Valid
        assertEquals(45 * 60_000L, valid.durationMillis)
        assertEquals(parseDeadline("24/12/2026 20:30"), valid.deadlineMillis)
    }
}
