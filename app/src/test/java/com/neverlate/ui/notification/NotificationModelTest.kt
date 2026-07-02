package com.neverlate.ui.notification

import com.neverlate.data.tasks.Task
import com.neverlate.domain.tasks.PendingTaskRow
import com.neverlate.ui.widget.PendingTasksWidgetModel
import com.neverlate.ui.widget.toWidgetModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the lock-screen notification's pure mapper. Mirrors [com.neverlate.ui.widget.PendingTasksWidgetStateTest]
 * on purpose: both surfaces map the same [Task] snapshot the same way (see the shared
 * `pendingRowsFor`), so their tests assert the same behaviours — plus the extra `totalPendingCount`
 * the notification needs for its redacted public version.
 */
class NotificationModelTest {

    @Test
    fun `empty task list produces the Empty model`() {
        val model = toNotificationModel(tasks = emptyList(), now = 0L)

        assertEquals(TasksNotificationModel.Empty, model)
    }

    @Test
    fun `single task produces Content with its row, count and most urgent`() {
        val task = Task(title = "Leer", estimatedDurationMillis = 5 * 60_000L)

        val model = toNotificationModel(tasks = listOf(task), now = 0L)

        assertTrue(model is TasksNotificationModel.Content)
        model as TasksNotificationModel.Content
        assertEquals(listOf(PendingTaskRow(title = "Leer", remaining = "05:00", isTimedOut = false)), model.rows)
        assertEquals(1, model.totalPendingCount)
        assertEquals("Leer", model.mostUrgent.title)
    }

    @Test
    fun `rows are sorted most-urgent-first regardless of the input order`() {
        val soon = Task(title = "Pronto", estimatedDurationMillis = 2 * 60_000L)
        val middle = Task(title = "Medio", estimatedDurationMillis = 5 * 60_000L)
        val later = Task(title = "Despues", estimatedDurationMillis = 10 * 60_000L)

        val model = toNotificationModel(tasks = listOf(later, soon, middle), now = 0L) as TasksNotificationModel.Content

        assertEquals(listOf("Pronto", "Medio", "Despues"), model.rows.map { it.title })
        assertEquals("Pronto", model.mostUrgent.title)
    }

    @Test
    fun `a timed-out task sorts first with zero remaining and isTimedOut true`() {
        val timedOut = Task(title = "Vencida", timerEndsAt = -1_000L)
        val active = Task(title = "Activa", estimatedDurationMillis = 5 * 60_000L)

        val model = toNotificationModel(tasks = listOf(active, timedOut), now = 0L) as TasksNotificationModel.Content

        assertEquals(
            listOf(
                PendingTaskRow(title = "Vencida", remaining = "00:00", isTimedOut = true),
                PendingTaskRow(title = "Activa", remaining = "05:00", isTimedOut = false),
            ),
            model.rows,
        )
    }

    @Test
    fun `more than five tasks cap the rows but the count reflects every pending task`() {
        val tasks = listOf(6, 3, 1, 5, 2, 4).map { minutes ->
            Task(title = "Tarea $minutes", estimatedDurationMillis = minutes * 60_000L)
        }

        val model = toNotificationModel(tasks = tasks, now = 0L) as TasksNotificationModel.Content

        // Only five rows are spelled out (capped), but the redacted public version must still say
        // there are six pending — hiding two in the count would under-report what is outstanding.
        assertEquals(5, model.rows.size)
        assertEquals(6, model.totalPendingCount)
        assertEquals(listOf("Tarea 1", "Tarea 2", "Tarea 3", "Tarea 4", "Tarea 5"), model.rows.map { it.title })
    }

    @Test
    fun `notification and widget produce identical rows for the same snapshot`() {
        // Locks in the "the two surfaces cannot quietly diverge" guarantee: both go through the
        // shared pendingRowsFor, so identical input must yield identical rows at any instant.
        val tasks = listOf(4, 1, 3, 2).map { minutes ->
            Task(title = "Tarea $minutes", estimatedDurationMillis = minutes * 60_000L)
        }
        val now = 30_000L

        val notificationRows = (toNotificationModel(tasks, now) as TasksNotificationModel.Content).rows
        val widgetRows = (toWidgetModel(tasks, now) as PendingTasksWidgetModel.Content).rows

        assertEquals(widgetRows, notificationRows)
    }
}
