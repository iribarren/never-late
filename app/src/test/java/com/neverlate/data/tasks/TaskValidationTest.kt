package com.neverlate.data.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskValidationTest {

    @Test
    fun `blank title is invalid even with a valid duration and deadline`() {
        val result = validateTaskForm(title = "   ", durationMinutesText = "30", deadlineText = "24/12/2026 20:30")

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.BLANK_TITLE, (result as TaskFormResult.Invalid).error)
    }

    @Test
    fun `title with neither duration nor deadline is invalid`() {
        val result = validateTaskForm(title = "Leer", durationMinutesText = "", deadlineText = "")

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.MISSING_DURATION_OR_DEADLINE, (result as TaskFormResult.Invalid).error)
    }

    @Test
    fun `non-numeric duration is invalid`() {
        val result = validateTaskForm(title = "Leer", durationMinutesText = "abc", deadlineText = "")

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.INVALID_DURATION, (result as TaskFormResult.Invalid).error)
    }

    @Test
    fun `zero or negative duration is invalid`() {
        val zero = validateTaskForm(title = "Leer", durationMinutesText = "0", deadlineText = "")
        val negative = validateTaskForm(title = "Leer", durationMinutesText = "-10", deadlineText = "")

        assertEquals(TaskValidationError.INVALID_DURATION, (zero as TaskFormResult.Invalid).error)
        assertEquals(TaskValidationError.INVALID_DURATION, (negative as TaskFormResult.Invalid).error)
    }

    @Test
    fun `malformed deadline text is invalid`() {
        val result = validateTaskForm(title = "Leer", durationMinutesText = "", deadlineText = "not a date")

        assertTrue(result is TaskFormResult.Invalid)
        assertEquals(TaskValidationError.INVALID_DEADLINE_FORMAT, (result as TaskFormResult.Invalid).error)
    }

    @Test
    fun `valid duration only produces Valid with a null deadline`() {
        val result = validateTaskForm(title = "Leer", durationMinutesText = "30", deadlineText = "")

        assertTrue(result is TaskFormResult.Valid)
        val valid = result as TaskFormResult.Valid
        assertEquals("Leer", valid.title)
        assertEquals(30 * 60_000L, valid.durationMillis)
        assertEquals(null, valid.deadlineMillis)
    }

    @Test
    fun `valid deadline only produces Valid with a null duration`() {
        val result = validateTaskForm(title = "Entregar", durationMinutesText = "", deadlineText = "24/12/2026 20:30")

        assertTrue(result is TaskFormResult.Valid)
        val valid = result as TaskFormResult.Valid
        assertEquals(null, valid.durationMillis)
        assertEquals(parseDeadline("24/12/2026 20:30"), valid.deadlineMillis)
    }

    @Test
    fun `valid duration and deadline together produce Valid with both values`() {
        val result = validateTaskForm(title = "Entregar", durationMinutesText = "45", deadlineText = "24/12/2026 20:30")

        assertTrue(result is TaskFormResult.Valid)
        val valid = result as TaskFormResult.Valid
        assertEquals(45 * 60_000L, valid.durationMillis)
        assertEquals(parseDeadline("24/12/2026 20:30"), valid.deadlineMillis)
    }

    @Test
    fun `title is trimmed before being stored`() {
        val result = validateTaskForm(title = "  Leer  ", durationMinutesText = "30", deadlineText = "")

        assertEquals("Leer", (result as TaskFormResult.Valid).title)
    }
}
