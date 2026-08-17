package com.neverlate.domain.tasks

import com.neverlate.data.tasks.Priority
import com.neverlate.data.tasks.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // widget-adaptive-layout (D2/AC-7): pendingRowsFor now propagates id and totalMillis from the
    // task, both needed only by the widget's large bucket (row completion action and progress
    // bar respectively).

    @Test
    fun `pendingRowsFor propagates the task's id and estimatedDurationMillis as totalMillis`() {
        val task = Task(id = 7L, title = "Con id", estimatedDurationMillis = 20 * 60_000L)

        val row = pendingRowsFor(listOf(task), now = 0L).single()

        assertEquals(7L, row.id)
        assertEquals(20 * 60_000L, row.totalMillis)
    }

    @Test
    fun `a task with no estimatedDurationMillis carries a null totalMillis on its row`() {
        // A task with only a deadline (no estimated duration) has no well-defined "total window" —
        // deadlineProgressFor already treats a null totalMillis as "no bar", so this row must
        // propagate exactly that, not a fabricated value.
        val task = Task(id = 3L, title = "Solo deadline", deadline = 60_000L)

        val row = pendingRowsFor(listOf(task), now = 0L).single()

        assertEquals(3L, row.id)
        assertEquals(null, row.totalMillis)
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

    // Bugfix: completed-tasks-in-passive-surfaces. pendingRowsFor must exclude any task whose
    // completedAt is non-null, regardless of its remaining-time data — a completed task is not
    // "pending" no matter how urgent its now-irrelevant countdown looks.

    @Test
    fun `a completed task with valid remaining-time data is excluded from the rows`() {
        val completed = Task(
            title = "Terminada",
            estimatedDurationMillis = 5 * 60_000L,
            completedAt = 1_000L,
        )

        val rows = pendingRowsFor(listOf(completed), now = 0L)

        assertEquals(emptyList<PendingTaskRow>(), rows)
    }

    @Test
    fun `a completed timed-out task does not sort in and does not push a pending task out of the cap`() {
        // Under the buggy behaviour, completedTimedOut's remaining millis clamp to 0 and sort
        // first, occupying one of the five row slots and evicting the least-urgent still-pending
        // task. It must be excluded outright instead.
        val completedTimedOut = Task(title = "Vencida y hecha", timerEndsAt = -1_000L, completedAt = 500L)
        val pendingTasks = listOf(1, 2, 3, 4, 5).map { minutes ->
            Task(title = "Pendiente $minutes", estimatedDurationMillis = minutes * 60_000L)
        }

        val rows = pendingRowsFor(listOf(completedTimedOut) + pendingTasks, now = 0L)

        assertEquals(5, rows.size)
        assertEquals(
            listOf("Pendiente 1", "Pendiente 2", "Pendiente 3", "Pendiente 4", "Pendiente 5"),
            rows.map { it.title },
        )
        assertTrue("a completed task must never appear among the rows", rows.none { it.title == "Vencida y hecha" })
    }

    @Test
    fun `when every task is completed pendingRowsFor returns an empty list`() {
        val tasks = listOf(1, 2, 3).map { minutes ->
            Task(title = "Hecha $minutes", estimatedDurationMillis = minutes * 60_000L, completedAt = 42L)
        }

        val rows = pendingRowsFor(tasks, now = 0L)

        assertEquals(emptyList<PendingTaskRow>(), rows)
    }
}
