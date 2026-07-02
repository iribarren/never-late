package com.neverlate.ui.widget

import com.neverlate.data.tasks.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingTasksWidgetStateTest {

    @Test
    fun `empty task list produces the Empty model`() {
        val model = toWidgetModel(tasks = emptyList(), now = 0L)

        assertEquals(PendingTasksWidgetModel.Empty, model)
    }

    @Test
    fun `single task produces Content with its title, formatted remaining time and not timed out`() {
        val task = Task(title = "Leer", estimatedDurationMillis = 5 * 60_000L)

        val model = toWidgetModel(tasks = listOf(task), now = 0L)

        assertTrue(model is PendingTasksWidgetModel.Content)
        val rows = (model as PendingTasksWidgetModel.Content).rows
        assertEquals(listOf(PendingTaskRow(title = "Leer", remaining = "05:00", isTimedOut = false)), rows)
    }

    @Test
    fun `rows are sorted most-urgent-first regardless of the input order`() {
        val soon = Task(title = "Pronto", estimatedDurationMillis = 2 * 60_000L)
        val middle = Task(title = "Medio", estimatedDurationMillis = 5 * 60_000L)
        val later = Task(title = "Despues", estimatedDurationMillis = 10 * 60_000L)

        // Scrambled input order on purpose: the function, not the caller, must produce the order.
        val model = toWidgetModel(tasks = listOf(later, soon, middle), now = 0L) as PendingTasksWidgetModel.Content

        assertEquals(listOf("Pronto", "Medio", "Despues"), model.rows.map { it.title })
    }

    @Test
    fun `a timed-out task sorts first with zero remaining and isTimedOut true`() {
        // Mirrors TaskTimingTest's "running task past its end instant clamps to zero": a
        // timerEndsAt already behind now, coerced to 0 remaining rather than a negative value.
        val timedOut = Task(title = "Vencida", timerEndsAt = -1_000L)
        val active = Task(title = "Activa", estimatedDurationMillis = 5 * 60_000L)

        val model = toWidgetModel(tasks = listOf(active, timedOut), now = 0L) as PendingTasksWidgetModel.Content

        assertEquals(
            listOf(
                PendingTaskRow(title = "Vencida", remaining = "00:00", isTimedOut = true),
                PendingTaskRow(title = "Activa", remaining = "05:00", isTimedOut = false),
            ),
            model.rows,
        )
    }

    @Test
    fun `more than five tasks are capped to the five most urgent, in order`() {
        // Scrambled input order on purpose, same reasoning as the sorting test above.
        val tasks = listOf(6, 3, 1, 5, 2, 4).map { minutes ->
            Task(title = "Tarea $minutes", estimatedDurationMillis = minutes * 60_000L)
        }

        val model = toWidgetModel(tasks = tasks, now = 0L) as PendingTasksWidgetModel.Content

        assertEquals(5, model.rows.size)
        assertEquals(listOf("Tarea 1", "Tarea 2", "Tarea 3", "Tarea 4", "Tarea 5"), model.rows.map { it.title })
        assertTrue("the least urgent task should not make the cut", model.rows.none { it.title == "Tarea 6" })
    }

    @Test
    fun `the h_mm_ss format boundary shows through in a widget row`() {
        val task = Task(title = "Larga", estimatedDurationMillis = 3_600_000L) // exactly one hour

        val model = toWidgetModel(tasks = listOf(task), now = 0L) as PendingTasksWidgetModel.Content

        assertEquals("1:00:00", model.rows.single().remaining)
    }
}
