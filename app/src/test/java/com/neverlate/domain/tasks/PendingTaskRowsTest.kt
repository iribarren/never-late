package com.neverlate.domain.tasks

import com.neverlate.data.tasks.Priority
import com.neverlate.data.tasks.Task
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Feature 05b: [pendingRowsFor] carrying [Priority] verbatim, and [PendingTaskRow.urgencyLevel].
 * Ordering/cap behaviour itself is already covered by [com.neverlate.ui.widget.PendingTasksWidgetStateTest]
 * and [com.neverlate.ui.notification.NotificationModelTest] — those existing tests passing
 * unmodified (their `PendingTaskRow(...)` calls never pass `priority`, relying on its default) is
 * the regression guard that adding this field changed no existing rule.
 */
class PendingTaskRowsTest {

    @Test
    fun `pendingRowsFor carries each of the four Priority values verbatim, without priority affecting order`() {
        // Priority ordinals ascend NONE < LOW < MEDIUM < HIGH — the exact opposite of how these
        // four tasks are ordered by remaining time below. A fixture where "sorted by urgency" and
        // "sorted by priority" happen to produce the same row order (e.g. the most-urgent task
        // also being the lowest priority) can't tell those two implementations apart; this one
        // can, because the two orderings are reversed of each other.
        val high = Task(title = "Alta", estimatedDurationMillis = 2 * 60_000L, priority = Priority.HIGH)
        val medium = Task(title = "Media", estimatedDurationMillis = 10 * 60_000L, priority = Priority.MEDIUM)
        val low = Task(title = "Baja", estimatedDurationMillis = 20 * 60_000L, priority = Priority.LOW)
        val none = Task(title = "Ninguna", estimatedDurationMillis = 45 * 60_000L, priority = Priority.NONE)

        val rows = pendingRowsFor(listOf(none, low, medium, high), now = 0L)

        assertEquals(listOf("Alta", "Media", "Baja", "Ninguna"), rows.map { it.title })
        assertEquals(listOf(Priority.HIGH, Priority.MEDIUM, Priority.LOW, Priority.NONE), rows.map { it.priority })
    }

    @Test
    fun `a task that never set a priority defaults to NONE on its row`() {
        val task = Task(title = "Sin prioridad", estimatedDurationMillis = 5 * 60_000L)

        val rows = pendingRowsFor(listOf(task), now = 0L)

        assertEquals(Priority.NONE, rows.single().priority)
    }

    @Test
    fun `urgencyLevel is Overdue at exactly zero remaining millis`() {
        val row = PendingTaskRow(title = "Vencida", remainingMillis = 0L)

        assertEquals(UrgencyLevel.Overdue, row.urgencyLevel())
    }

    @Test
    fun `urgencyLevel is Urgent at the 5-minute boundary`() {
        val row = PendingTaskRow(title = "Urgente", remainingMillis = 5 * 60_000L)

        assertEquals(UrgencyLevel.Urgent, row.urgencyLevel())
    }

    @Test
    fun `urgencyLevel is Soon just past the 5-minute boundary and at the 60-minute boundary`() {
        val justPastUrgent = PendingTaskRow(title = "Pronto1", remainingMillis = 5 * 60_000L + 1)
        val atSoonBoundary = PendingTaskRow(title = "Pronto2", remainingMillis = 60 * 60_000L)

        assertEquals(UrgencyLevel.Soon, justPastUrgent.urgencyLevel())
        assertEquals(UrgencyLevel.Soon, atSoonBoundary.urgencyLevel())
    }

    @Test
    fun `urgencyLevel is Calm just past the 60-minute boundary`() {
        val row = PendingTaskRow(title = "Calma", remainingMillis = 60 * 60_000L + 1)

        assertEquals(UrgencyLevel.Calm, row.urgencyLevel())
    }
}
