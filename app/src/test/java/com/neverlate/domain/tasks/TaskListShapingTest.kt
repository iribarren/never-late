package com.neverlate.domain.tasks

import com.neverlate.data.tasks.Task
import com.neverlate.ui.tasks.TaskUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for `TaskListShaping.kt` (feature 03b): [filteredBy] (US-1), [sortedBy] (US-2),
 * [groupedByUrgency] (US-3), and the [shapedBy] pipeline that composes all three + [isEmpty]
 * (US-4's `NoResults` signal). Everything under test takes and returns plain values — same
 * "pure function, no fake clock/emulator needed" style as [UrgencyTest] and
 * [ReminderPlanningTest].
 */
class TaskListShapingTest {

    private val urgentThresholdMillis = 5 * 60_000L
    private val soonThresholdMillis = 60 * 60_000L

    /** Builds a [TaskUiModel] with only the fields a given test cares about. */
    private fun uiModel(
        id: Long,
        title: String,
        deadline: Long? = null,
        remainingMillis: Long = 0L,
        isTimedOut: Boolean = false,
    ): TaskUiModel = TaskUiModel(
        task = Task(id = id, title = title, deadline = deadline),
        remainingMillis = remainingMillis,
        isTimedOut = isTimedOut,
    )

    // filteredBy (US-1) --------------------------------------------------------------------------

    @Test
    fun `blank query returns the full list unchanged`() {
        val tasks = listOf(uiModel(1, "Preparar presentación"), uiModel(2, "Enviar informe"))

        assertEquals(tasks, tasks.filteredBy(""))
    }

    @Test
    fun `whitespace-only query is treated as blank, no filter applied`() {
        val tasks = listOf(uiModel(1, "Preparar presentación"), uiModel(2, "Enviar informe"))

        assertEquals(tasks, tasks.filteredBy("   "))
    }

    @Test
    fun `filter matches as a case-insensitive substring`() {
        val presentation = uiModel(1, "Preparar Presentación")
        val report = uiModel(2, "Enviar informe")

        assertEquals(listOf(presentation), listOf(presentation, report).filteredBy("pres"))
    }

    @Test
    fun `filter matches a substring anywhere in the title, not just at the start`() {
        val task = uiModel(1, "Enviar el informe final")

        assertEquals(listOf(task), listOf(task).filteredBy("informe"))
    }

    @Test
    fun `filter with no matching title returns an empty list`() {
        val tasks = listOf(uiModel(1, "Preparar presentación"), uiModel(2, "Enviar informe"))

        assertTrue(tasks.filteredBy("xyz").isEmpty())
    }

    @Test
    fun `filter on an empty input list returns an empty list`() {
        assertTrue(emptyList<TaskUiModel>().filteredBy("anything").isEmpty())
    }

    // sortedBy (US-2) -----------------------------------------------------------------------------

    @Test
    fun `sortedBy deadline ascending orders the soonest deadline first`() {
        val soon = uiModel(1, "Soon", deadline = 1_000L)
        val later = uiModel(2, "Later", deadline = 2_000L)

        val result = listOf(later, soon).sortedBy(TaskSortField.Deadline, SortDirection.Ascending)

        assertEquals(listOf(soon, later), result)
    }

    @Test
    fun `sortedBy deadline descending orders the latest deadline first`() {
        val soon = uiModel(1, "Soon", deadline = 1_000L)
        val later = uiModel(2, "Later", deadline = 2_000L)

        val result = listOf(soon, later).sortedBy(TaskSortField.Deadline, SortDirection.Descending)

        assertEquals(listOf(later, soon), result)
    }

    @Test
    fun `sortedBy deadline ascending places a null deadline last`() {
        val noDeadline = uiModel(1, "No deadline", deadline = null)
        val hasDeadline = uiModel(2, "Has deadline", deadline = 1_000L)

        val result = listOf(noDeadline, hasDeadline).sortedBy(TaskSortField.Deadline, SortDirection.Ascending)

        assertEquals(listOf(hasDeadline, noDeadline), result)
    }

    @Test
    fun `sortedBy deadline descending also places a null deadline last`() {
        // The spec calls out that null deadlines go last in BOTH directions, unlike a naive
        // reversed comparator that would put them first when descending.
        val noDeadline = uiModel(1, "No deadline", deadline = null)
        val hasDeadline = uiModel(2, "Has deadline", deadline = 1_000L)

        val result = listOf(noDeadline, hasDeadline).sortedBy(TaskSortField.Deadline, SortDirection.Descending)

        assertEquals(listOf(hasDeadline, noDeadline), result)
    }

    @Test
    fun `sortedBy title ascending orders A to Z, case-insensitively`() {
        val banana = uiModel(1, "banana")
        val apple = uiModel(2, "Apple")

        val result = listOf(banana, apple).sortedBy(TaskSortField.Title, SortDirection.Ascending)

        assertEquals(listOf(apple, banana), result)
    }

    @Test
    fun `sortedBy title descending orders Z to A, case-insensitively`() {
        val banana = uiModel(1, "banana")
        val apple = uiModel(2, "Apple")

        val result = listOf(apple, banana).sortedBy(TaskSortField.Title, SortDirection.Descending)

        assertEquals(listOf(banana, apple), result)
    }

    @Test
    fun `sortedBy deadline is stable - equal deadlines keep their original relative order`() {
        val first = uiModel(1, "First", deadline = 1_000L)
        val second = uiModel(2, "Second", deadline = 1_000L)
        val third = uiModel(3, "Third", deadline = 1_000L)

        val result = listOf(first, second, third).sortedBy(TaskSortField.Deadline, SortDirection.Ascending)

        assertEquals(listOf(first, second, third), result)
    }

    @Test
    fun `sortedBy title is stable - equal titles (case-insensitive) keep their original relative order`() {
        val first = uiModel(1, "tarea")
        val second = uiModel(2, "TAREA")

        val result = listOf(first, second).sortedBy(TaskSortField.Title, SortDirection.Ascending)

        assertEquals(listOf(first, second), result)
    }

    @Test
    fun `sortedBy deadline with every task having a null deadline keeps the original relative order`() {
        val first = uiModel(1, "First")
        val second = uiModel(2, "Second")
        val third = uiModel(3, "Third")

        val result = listOf(first, second, third).sortedBy(TaskSortField.Deadline, SortDirection.Descending)

        assertEquals(listOf(first, second, third), result)
    }

    @Test
    fun `sortedBy on an empty input list returns an empty list`() {
        assertTrue(emptyList<TaskUiModel>().sortedBy(TaskSortField.Deadline, SortDirection.Ascending).isEmpty())
    }

    @Test
    fun `sortedBy on a single-element list returns it unchanged`() {
        val only = uiModel(1, "Only")

        assertEquals(listOf(only), listOf(only).sortedBy(TaskSortField.Title, SortDirection.Descending))
    }

    // groupedByUrgency (US-3) ----------------------------------------------------------------------

    @Test
    fun `groupedByUrgency buckets tasks by urgencyLevelFor`() {
        val overdue = uiModel(1, "Overdue", remainingMillis = 0L, isTimedOut = true)
        val urgent = uiModel(2, "Urgent", remainingMillis = urgentThresholdMillis, isTimedOut = false)
        val soon = uiModel(3, "Soon", remainingMillis = soonThresholdMillis, isTimedOut = false)
        val calm = uiModel(4, "Calm", remainingMillis = soonThresholdMillis + 1, isTimedOut = false)

        val grouped = listOf(overdue, urgent, soon, calm).groupedByUrgency()

        assertEquals(listOf(overdue), grouped[UrgencyLevel.Overdue])
        assertEquals(listOf(urgent), grouped[UrgencyLevel.Urgent])
        assertEquals(listOf(soon), grouped[UrgencyLevel.Soon])
        assertEquals(listOf(calm), grouped[UrgencyLevel.Calm])
    }

    @Test
    fun `groupedByUrgency omits urgency levels with no matching task`() {
        val calm = uiModel(1, "Calm", remainingMillis = soonThresholdMillis + 1, isTimedOut = false)

        val grouped = listOf(calm).groupedByUrgency()

        assertEquals(setOf(UrgencyLevel.Calm), grouped.keys)
    }

    @Test
    fun `groupedByUrgency on an empty input list returns an empty map`() {
        assertTrue(emptyList<TaskUiModel>().groupedByUrgency().isEmpty())
    }

    // isEmpty (US-4) --------------------------------------------------------------------------------

    @Test
    fun `isEmpty is true for an empty Flat result`() {
        assertTrue(ShapedTaskList.Flat(emptyList()).isEmpty())
    }

    @Test
    fun `isEmpty is false for a non-empty Flat result`() {
        assertFalse(ShapedTaskList.Flat(listOf(uiModel(1, "Task"))).isEmpty())
    }

    @Test
    fun `isEmpty is true for a Grouped result with no sections`() {
        assertTrue(ShapedTaskList.Grouped(emptyMap()).isEmpty())
    }

    @Test
    fun `isEmpty is false for a Grouped result with at least one section`() {
        val sections = mapOf(UrgencyLevel.Calm to listOf(uiModel(1, "Task")))

        assertFalse(ShapedTaskList.Grouped(sections).isEmpty())
    }

    // shapedBy pipeline (filter -> sort -> group) ----------------------------------------------------

    @Test
    fun `shapedBy with grouping off returns a Flat result`() {
        val shaped = listOf(uiModel(1, "Task")).shapedBy("", TaskListCriteria(grouped = false))

        assertTrue(shaped is ShapedTaskList.Flat)
    }

    @Test
    fun `shapedBy with grouping on returns a Grouped result`() {
        val shaped = listOf(uiModel(1, "Task")).shapedBy("", TaskListCriteria(grouped = true))

        assertTrue(shaped is ShapedTaskList.Grouped)
    }

    @Test
    fun `shapedBy grouped sections are ordered Overdue, Urgent, Soon, Calm`() {
        val overdue = uiModel(1, "Overdue", remainingMillis = 0L, isTimedOut = true)
        val urgent = uiModel(2, "Urgent", remainingMillis = urgentThresholdMillis, isTimedOut = false)
        val soon = uiModel(3, "Soon", remainingMillis = soonThresholdMillis, isTimedOut = false)
        val calm = uiModel(4, "Calm", remainingMillis = soonThresholdMillis + 1, isTimedOut = false)

        // Deliberately out of display order in the input, to prove shapedBy re-orders it rather
        // than relying on groupBy's first-seen order.
        val shaped = listOf(calm, soon, urgent, overdue)
            .shapedBy("", TaskListCriteria(grouped = true)) as ShapedTaskList.Grouped

        assertEquals(
            listOf(UrgencyLevel.Overdue, UrgencyLevel.Urgent, UrgencyLevel.Soon, UrgencyLevel.Calm),
            shaped.sections.keys.toList(),
        )
    }

    @Test
    fun `shapedBy grouped omits empty urgency sections`() {
        val calm = uiModel(1, "Calm", remainingMillis = soonThresholdMillis + 1, isTimedOut = false)

        val shaped = listOf(calm).shapedBy("", TaskListCriteria(grouped = true)) as ShapedTaskList.Grouped

        assertEquals(setOf(UrgencyLevel.Calm), shaped.sections.keys)
    }

    @Test
    fun `shapedBy filters before grouping - a non-matching task never appears in any section`() {
        val matchingUrgent = uiModel(1, "Preparar informe", remainingMillis = urgentThresholdMillis, isTimedOut = false)
        val matchingCalm = uiModel(2, "Informe final", remainingMillis = soonThresholdMillis + 1, isTimedOut = false)
        // Would sort first by urgency (Overdue) if it survived filtering - it must not appear at all.
        val nonMatching = uiModel(3, "Otra tarea", remainingMillis = 0L, isTimedOut = true)

        val shaped = listOf(nonMatching, matchingCalm, matchingUrgent)
            .shapedBy("informe", TaskListCriteria(grouped = true)) as ShapedTaskList.Grouped

        assertEquals(listOf(UrgencyLevel.Urgent, UrgencyLevel.Calm), shaped.sections.keys.toList())
        assertEquals(listOf(matchingUrgent), shaped.sections[UrgencyLevel.Urgent])
        assertEquals(listOf(matchingCalm), shaped.sections[UrgencyLevel.Calm])
    }

    @Test
    fun `shapedBy sorts within each section, not across the whole list`() {
        val urgentB = uiModel(1, "B urgent", remainingMillis = urgentThresholdMillis, isTimedOut = false)
        val urgentA = uiModel(2, "A urgent", remainingMillis = urgentThresholdMillis, isTimedOut = false)
        val calmB = uiModel(3, "B calm", remainingMillis = soonThresholdMillis + 1, isTimedOut = false)
        val calmA = uiModel(4, "A calm", remainingMillis = soonThresholdMillis + 1, isTimedOut = false)

        val criteria = TaskListCriteria(sortField = TaskSortField.Title, direction = SortDirection.Ascending, grouped = true)
        val shaped = listOf(urgentB, urgentA, calmB, calmA).shapedBy("", criteria) as ShapedTaskList.Grouped

        assertEquals(listOf(urgentA, urgentB), shaped.sections[UrgencyLevel.Urgent])
        assertEquals(listOf(calmA, calmB), shaped.sections[UrgencyLevel.Calm])
    }

    @Test
    fun `shapedBy ungrouped applies the filter, then the sort`() {
        val zeta = uiModel(1, "Zeta informe")
        val alfa = uiModel(2, "Alfa informe")
        val excluded = uiModel(3, "Otra cosa")

        val criteria = TaskListCriteria(sortField = TaskSortField.Title, direction = SortDirection.Ascending)
        val shaped = listOf(zeta, alfa, excluded).shapedBy("informe", criteria) as ShapedTaskList.Flat

        assertEquals(listOf(alfa, zeta), shaped.tasks)
    }

    @Test
    fun `shapedBy on an empty input list ungrouped returns an empty Flat result`() {
        val shaped = emptyList<TaskUiModel>().shapedBy("", TaskListCriteria(grouped = false))

        assertTrue(shaped is ShapedTaskList.Flat)
        assertTrue(shaped.isEmpty())
    }

    @Test
    fun `shapedBy on an empty input list grouped returns an empty Grouped result`() {
        val shaped = emptyList<TaskUiModel>().shapedBy("", TaskListCriteria(grouped = true))

        assertTrue(shaped is ShapedTaskList.Grouped)
        assertTrue(shaped.isEmpty())
    }
}
