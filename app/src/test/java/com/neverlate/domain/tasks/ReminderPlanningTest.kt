package com.neverlate.domain.tasks

import com.neverlate.data.tasks.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPlanningTest {

    // minutesToMillis ---------------------------------------------------------------------------

    @Test
    fun `minutesToMillis converts zero minutes to zero millis`() {
        assertEquals(0L, minutesToMillis(0))
    }

    @Test
    fun `minutesToMillis converts ten minutes to 600000 millis`() {
        assertEquals(600_000L, minutesToMillis(10))
    }

    @Test
    fun `minutesToMillis converts sixty minutes to 3600000 millis`() {
        assertEquals(3_600_000L, minutesToMillis(60))
    }

    // reminderTimeFor -----------------------------------------------------------------------------

    @Test
    fun `reminderTimeFor subtracts the lead time from the deadline`() {
        val task = Task(title = "Entregar informe", deadline = 1_000_000L)

        assertEquals(1_000_000L - 600_000L, reminderTimeFor(task, leadMillis = 600_000L))
    }

    @Test
    fun `reminderTimeFor returns null for a task without a deadline`() {
        val task = Task(title = "Sin vencimiento", deadline = null)

        assertNull(reminderTimeFor(task, leadMillis = 600_000L))
    }

    @Test
    fun `reminderTimeFor with a zero lead equals the deadline itself`() {
        val task = Task(title = "Entregar informe", deadline = 1_000_000L)

        assertEquals(1_000_000L, reminderTimeFor(task, leadMillis = 0L))
    }

    @Test
    fun `reminderTimeFor with a lead larger than the margin to deadline returns a past instant unclamped`() {
        // Deadline only 100_000ms away, but the lead requested is 600_000ms — the raw subtraction
        // lands well before now/deadline. reminderTimeFor itself does not know "now" and must not
        // clamp; isReminderInFuture/remindersToSchedule are what filter this out downstream.
        val task = Task(title = "Vence pronto", deadline = 1_000_000L)

        val result = reminderTimeFor(task, leadMillis = 600_000L)

        assertEquals(1_000_000L - 600_000L, result)
        assertTrue("expected a negative offset from the deadline", result!! < task.deadline!!)
    }

    // isReminderInFuture --------------------------------------------------------------------------

    @Test
    fun `isReminderInFuture is false when the reminder instant is in the past`() {
        assertFalse(isReminderInFuture(reminderAtMillis = 500L, now = 1_000L))
    }

    @Test
    fun `isReminderInFuture is false when the reminder instant exactly equals now (strict greater-than)`() {
        assertFalse(isReminderInFuture(reminderAtMillis = 1_000L, now = 1_000L))
    }

    @Test
    fun `isReminderInFuture is true when the reminder instant is in the future`() {
        assertTrue(isReminderInFuture(reminderAtMillis = 1_500L, now = 1_000L))
    }

    // remindersToSchedule -------------------------------------------------------------------------

    @Test
    fun `remindersToSchedule returns an empty list for an empty task list`() {
        assertEquals(emptyList<ReminderPlan>(), remindersToSchedule(emptyList(), now = 1_000_000L, leadMillis = 600_000L))
    }

    @Test
    fun `remindersToSchedule keeps only tasks with a deadline whose reminder instant is still future`() {
        val now = 1_000_000L
        val leadMillis = 600_000L

        // Deadline far enough away that deadline - lead is still ahead of now.
        val futureTask = Task(id = 1, title = "Futura", deadline = now + 1_000_000L)
        // Deadline close enough that deadline - lead already lies in the past.
        val pastReminderTask = Task(id = 2, title = "Aviso ya pasado", deadline = now + 100_000L)
        // No deadline at all: never produces a reminder regardless of "now".
        val noDeadlineTask = Task(id = 3, title = "Sin vencimiento", deadline = null)
        // Deadline exactly lead-time away: reminder instant equals now exactly, excluded (strict >).
        val exactlyNowTask = Task(id = 4, title = "Aviso justo ahora", deadline = now + leadMillis)

        val tasks = listOf(futureTask, pastReminderTask, noDeadlineTask, exactlyNowTask)

        val result = remindersToSchedule(tasks, now = now, leadMillis = leadMillis)

        assertEquals(listOf(ReminderPlan(taskId = 1, triggerAtMillis = now + 1_000_000L - leadMillis)), result)
    }

    @Test
    fun `remindersToSchedule applies the given lead time to compute each trigger instant`() {
        val now = 0L
        val task = Task(id = 7, title = "Con antelación distinta", deadline = 10_000_000L)

        val withShortLead = remindersToSchedule(listOf(task), now = now, leadMillis = 1_000_000L)
        val withLongLead = remindersToSchedule(listOf(task), now = now, leadMillis = 5_000_000L)

        assertEquals(listOf(ReminderPlan(7, 9_000_000L)), withShortLead)
        assertEquals(listOf(ReminderPlan(7, 5_000_000L)), withLongLead)
    }

    @Test
    fun `remindersToSchedule preserves task order for multiple qualifying tasks`() {
        val now = 0L
        val leadMillis = 100L
        val first = Task(id = 10, title = "Primera", deadline = 10_000L)
        val second = Task(id = 20, title = "Segunda", deadline = 20_000L)

        val result = remindersToSchedule(listOf(first, second), now = now, leadMillis = leadMillis)

        assertEquals(
            listOf(
                ReminderPlan(10, 10_000L - leadMillis),
                ReminderPlan(20, 20_000L - leadMillis),
            ),
            result,
        )
    }
}
