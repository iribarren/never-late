package com.neverlate.ui.widget

import com.neverlate.data.tasks.Priority
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
    fun `single task produces Content with its title and raw remainingMillis`() {
        // Feature 20b: the row carries raw millis, not a pre-formatted string — turning it into
        // text is PendingTaskRowContent's job via formatRemainingLabel, not this pure mapper's.
        val task = Task(title = "Leer", estimatedDurationMillis = 5 * 60_000L)

        val model = toWidgetModel(tasks = listOf(task), now = 0L)

        assertTrue(model is PendingTasksWidgetModel.Content)
        val rows = (model as PendingTasksWidgetModel.Content).rows
        assertEquals(listOf(PendingTaskRow(title = "Leer", remainingMillis = 5 * 60_000L)), rows)
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
    fun `a timed-out task sorts first with zero remainingMillis`() {
        // Mirrors TaskTimingTest's "running task past its end instant clamps to zero": a
        // timerEndsAt already behind now, coerced to 0 remaining rather than a negative value.
        // isTimedOut no longer exists on the row (feature 20b) — it is derived as
        // remainingMillis == 0L where needed, so the assertion here is on the raw value.
        val timedOut = Task(title = "Vencida", timerEndsAt = -1_000L)
        val active = Task(title = "Activa", estimatedDurationMillis = 5 * 60_000L)

        val model = toWidgetModel(tasks = listOf(active, timedOut), now = 0L) as PendingTasksWidgetModel.Content

        assertEquals(
            listOf(
                PendingTaskRow(title = "Vencida", remainingMillis = 0L),
                PendingTaskRow(title = "Activa", remainingMillis = 5 * 60_000L),
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
    fun `an exact one-hour duration task carries the raw millis value, unrounded`() {
        // Guards against a lossy pass-through in this layer specifically (e.g. an accidental
        // re-introduction of formatting or rounding here) — the shape/label decisions themselves
        // are the classifier's job and are covered exhaustively by RemainingTimeTest.
        val task = Task(title = "Larga", estimatedDurationMillis = 3_600_000L) // exactly one hour

        val model = toWidgetModel(tasks = listOf(task), now = 0L) as PendingTasksWidgetModel.Content

        assertEquals(3_600_000L, model.rows.single().remainingMillis)
    }

    @Test
    fun `a task list containing only completed tasks yields Empty, not Content with zero rows`() {
        // Bugfix: completed-tasks-in-passive-surfaces. toWidgetModel only checks tasks.isEmpty()
        // for the Empty decision, so a non-empty list of exclusively completed tasks currently
        // falls through to Content(rows = emptyList()) instead of Empty.
        val tasks = listOf(1, 2, 3).map { minutes ->
            Task(title = "Hecha $minutes", estimatedDurationMillis = minutes * 60_000L, completedAt = 99L)
        }

        val model = toWidgetModel(tasks = tasks, now = 0L)

        assertEquals(PendingTasksWidgetModel.Empty, model)
    }

    @Test
    fun `a task's priority is carried through to the widget row`() {
        // Feature 05b: pendingRowsFor (and therefore toWidgetModel) now fills PendingTaskRow's
        // priority field from the task instead of leaving it at its NONE default.
        val task = Task(title = "Importante", estimatedDurationMillis = 5 * 60_000L, priority = Priority.HIGH)

        val model = toWidgetModel(tasks = listOf(task), now = 0L) as PendingTasksWidgetModel.Content

        assertEquals(Priority.HIGH, model.rows.single().priority)
    }
}
