package com.neverlate.domain.tasks

import com.neverlate.data.tasks.Priority
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

    /** Builds a [TaskUiModel] with only the fields a given test cares about. [priority] and
     *  [completedAt] default to their pre-`priority-sorting` values (NONE / pending) so every
     *  existing call site below is unaffected. */
    private fun uiModel(
        id: Long,
        title: String,
        deadline: Long? = null,
        remainingMillis: Long = 0L,
        isTimedOut: Boolean = false,
        priority: Priority = Priority.NONE,
        completedAt: Long? = null,
    ): TaskUiModel = TaskUiModel(
        task = Task(id = id, title = title, deadline = deadline, priority = priority, completedAt = completedAt),
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

    /** Reads the [UrgencyLevel] keys of a [groupedByUrgency] result, in order. */
    private fun List<TaskSection>.urgencyKeys(): List<UrgencyLevel> =
        map { (it.key as TaskGroupKey.ByUrgency).level }

    private fun List<TaskSection>.tasksFor(level: UrgencyLevel): List<TaskUiModel>? =
        firstOrNull { (it.key as TaskGroupKey.ByUrgency).level == level }?.tasks

    @Test
    fun `groupedByUrgency buckets tasks by urgencyLevelFor`() {
        val overdue = uiModel(1, "Overdue", remainingMillis = 0L, isTimedOut = true)
        val urgent = uiModel(2, "Urgent", remainingMillis = urgentThresholdMillis, isTimedOut = false)
        val soon = uiModel(3, "Soon", remainingMillis = soonThresholdMillis, isTimedOut = false)
        val calm = uiModel(4, "Calm", remainingMillis = soonThresholdMillis + 1, isTimedOut = false)

        val grouped = listOf(overdue, urgent, soon, calm).groupedByUrgency()

        assertEquals(listOf(overdue), grouped.tasksFor(UrgencyLevel.Overdue))
        assertEquals(listOf(urgent), grouped.tasksFor(UrgencyLevel.Urgent))
        assertEquals(listOf(soon), grouped.tasksFor(UrgencyLevel.Soon))
        assertEquals(listOf(calm), grouped.tasksFor(UrgencyLevel.Calm))
    }

    @Test
    fun `groupedByUrgency omits urgency levels with no matching task`() {
        val calm = uiModel(1, "Calm", remainingMillis = soonThresholdMillis + 1, isTimedOut = false)

        val grouped = listOf(calm).groupedByUrgency()

        assertEquals(setOf(UrgencyLevel.Calm), grouped.urgencyKeys().toSet())
    }

    @Test
    fun `groupedByUrgency on an empty input list returns an empty list`() {
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
        assertTrue(ShapedTaskList.Grouped(emptyList()).isEmpty())
    }

    @Test
    fun `isEmpty is false for a Grouped result with at least one section`() {
        val sections = listOf(TaskSection(TaskGroupKey.ByUrgency(UrgencyLevel.Calm), listOf(uiModel(1, "Task"))))

        assertFalse(ShapedTaskList.Grouped(sections).isEmpty())
    }

    // shapedBy pipeline (filter -> sort -> group) ----------------------------------------------------

    @Test
    fun `shapedBy with grouping off returns a Flat result`() {
        val shaped = listOf(uiModel(1, "Task")).shapedBy("", TaskListCriteria(groupAxis = TaskGroupAxis.None))

        assertTrue(shaped is ShapedTaskList.Flat)
    }

    @Test
    fun `shapedBy with urgency grouping on returns a Grouped result`() {
        val shaped = listOf(uiModel(1, "Task")).shapedBy("", TaskListCriteria(groupAxis = TaskGroupAxis.Urgency))

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
            .shapedBy("", TaskListCriteria(groupAxis = TaskGroupAxis.Urgency)) as ShapedTaskList.Grouped

        assertEquals(
            listOf(UrgencyLevel.Overdue, UrgencyLevel.Urgent, UrgencyLevel.Soon, UrgencyLevel.Calm),
            shaped.sections.map { (it.key as TaskGroupKey.ByUrgency).level },
        )
    }

    @Test
    fun `shapedBy grouped omits empty urgency sections`() {
        val calm = uiModel(1, "Calm", remainingMillis = soonThresholdMillis + 1, isTimedOut = false)

        val shaped = listOf(calm).shapedBy("", TaskListCriteria(groupAxis = TaskGroupAxis.Urgency)) as ShapedTaskList.Grouped

        assertEquals(listOf(UrgencyLevel.Calm), shaped.sections.map { (it.key as TaskGroupKey.ByUrgency).level })
    }

    @Test
    fun `shapedBy filters before grouping - a non-matching task never appears in any section`() {
        val matchingUrgent = uiModel(1, "Preparar informe", remainingMillis = urgentThresholdMillis, isTimedOut = false)
        val matchingCalm = uiModel(2, "Informe final", remainingMillis = soonThresholdMillis + 1, isTimedOut = false)
        // Would sort first by urgency (Overdue) if it survived filtering - it must not appear at all.
        val nonMatching = uiModel(3, "Otra tarea", remainingMillis = 0L, isTimedOut = true)

        val shaped = listOf(nonMatching, matchingCalm, matchingUrgent)
            .shapedBy("informe", TaskListCriteria(groupAxis = TaskGroupAxis.Urgency)) as ShapedTaskList.Grouped

        assertEquals(
            listOf(UrgencyLevel.Urgent, UrgencyLevel.Calm),
            shaped.sections.map { (it.key as TaskGroupKey.ByUrgency).level },
        )
        assertEquals(listOf(matchingUrgent), shaped.sections[0].tasks)
        assertEquals(listOf(matchingCalm), shaped.sections[1].tasks)
    }

    @Test
    fun `shapedBy sorts within each section, not across the whole list`() {
        val urgentB = uiModel(1, "B urgent", remainingMillis = urgentThresholdMillis, isTimedOut = false)
        val urgentA = uiModel(2, "A urgent", remainingMillis = urgentThresholdMillis, isTimedOut = false)
        val calmB = uiModel(3, "B calm", remainingMillis = soonThresholdMillis + 1, isTimedOut = false)
        val calmA = uiModel(4, "A calm", remainingMillis = soonThresholdMillis + 1, isTimedOut = false)

        val criteria = TaskListCriteria(
            sortField = TaskSortField.Title,
            direction = SortDirection.Ascending,
            groupAxis = TaskGroupAxis.Urgency,
        )
        val shaped = listOf(urgentB, urgentA, calmB, calmA).shapedBy("", criteria) as ShapedTaskList.Grouped

        assertEquals(listOf(urgentA, urgentB), shaped.sections.first { (it.key as TaskGroupKey.ByUrgency).level == UrgencyLevel.Urgent }.tasks)
        assertEquals(listOf(calmA, calmB), shaped.sections.first { (it.key as TaskGroupKey.ByUrgency).level == UrgencyLevel.Calm }.tasks)
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
        val shaped = emptyList<TaskUiModel>().shapedBy("", TaskListCriteria(groupAxis = TaskGroupAxis.None))

        assertTrue(shaped is ShapedTaskList.Flat)
        assertTrue(shaped.isEmpty())
    }

    @Test
    fun `shapedBy on an empty input list grouped returns an empty Grouped result`() {
        val shaped = emptyList<TaskUiModel>().shapedBy("", TaskListCriteria(groupAxis = TaskGroupAxis.Urgency))

        assertTrue(shaped is ShapedTaskList.Grouped)
        assertTrue(shaped.isEmpty())
    }

    // filteredByPriority (D5) ------------------------------------------------------------------------

    private fun uiModelWithPriority(id: Long, title: String, priority: Priority): TaskUiModel =
        TaskUiModel(task = Task(id = id, title = title, priority = priority), remainingMillis = 0L, isTimedOut = false)

    @Test
    fun `filteredByPriority with an empty set returns the full list unchanged`() {
        val high = uiModelWithPriority(1, "High", Priority.HIGH)
        val none = uiModelWithPriority(2, "None", Priority.NONE)

        assertEquals(listOf(high, none), listOf(high, none).filteredByPriority(emptySet()))
    }

    @Test
    fun `filteredByPriority keeps only tasks whose priority is in the set`() {
        val high = uiModelWithPriority(1, "High", Priority.HIGH)
        val low = uiModelWithPriority(2, "Low", Priority.LOW)

        assertEquals(listOf(high), listOf(high, low).filteredByPriority(setOf(Priority.HIGH)))
    }

    @Test
    fun `filteredByPriority supports multiple selected priorities`() {
        val high = uiModelWithPriority(1, "High", Priority.HIGH)
        val medium = uiModelWithPriority(2, "Medium", Priority.MEDIUM)
        val low = uiModelWithPriority(3, "Low", Priority.LOW)

        val result = listOf(high, medium, low).filteredByPriority(setOf(Priority.HIGH, Priority.MEDIUM))

        assertEquals(listOf(high, medium), result)
    }

    // sortedBy Priority (D3) -------------------------------------------------------------------------

    @Test
    fun `sortedBy priority ascending orders most important first`() {
        val low = uiModelWithPriority(1, "Low", Priority.LOW)
        val high = uiModelWithPriority(2, "High", Priority.HIGH)
        val none = uiModelWithPriority(3, "None", Priority.NONE)
        val medium = uiModelWithPriority(4, "Medium", Priority.MEDIUM)

        val result = listOf(low, high, none, medium).sortedBy(TaskSortField.Priority, SortDirection.Ascending)

        assertEquals(listOf(high, medium, low, none), result)
    }

    @Test
    fun `sortedBy priority descending orders least important first`() {
        val low = uiModelWithPriority(1, "Low", Priority.LOW)
        val high = uiModelWithPriority(2, "High", Priority.HIGH)

        val result = listOf(high, low).sortedBy(TaskSortField.Priority, SortDirection.Descending)

        assertEquals(listOf(low, high), result)
    }

    // groupedByPriority (D6) --------------------------------------------------------------------------

    @Test
    fun `groupedByPriority sections are ordered Alta, Media, Baja, Sin prioridad`() {
        val high = uiModelWithPriority(1, "High", Priority.HIGH)
        val medium = uiModelWithPriority(2, "Medium", Priority.MEDIUM)
        val low = uiModelWithPriority(3, "Low", Priority.LOW)
        val none = uiModelWithPriority(4, "None", Priority.NONE)

        val sections = listOf(none, low, medium, high).groupedByPriority()

        assertEquals(
            listOf(Priority.HIGH, Priority.MEDIUM, Priority.LOW, Priority.NONE),
            sections.map { (it.key as TaskGroupKey.ByPriority).priority },
        )
    }

    @Test
    fun `groupedByPriority drops empty sections`() {
        val high = uiModelWithPriority(1, "High", Priority.HIGH)

        val sections = listOf(high).groupedByPriority()

        assertEquals(listOf(Priority.HIGH), sections.map { (it.key as TaskGroupKey.ByPriority).priority })
    }

    @Test
    fun `groupedByPriority keeps a completed task under its real priority, not a catch-all`() {
        val completedHigh = TaskUiModel(
            task = Task(id = 1, title = "Done", priority = Priority.HIGH, completedAt = 1_000L),
            remainingMillis = 0L,
            isTimedOut = false,
        )

        val sections = listOf(completedHigh).groupedByPriority()

        assertEquals(listOf(Priority.HIGH), sections.map { (it.key as TaskGroupKey.ByPriority).priority })
        assertEquals(listOf(completedHigh), sections.single().tasks)
    }

    // groupedByUrgency regression guard (D6): completed tasks are STILL forced into Calm on the
    // urgency axis - unlike groupedByPriority above, this behaviour is unchanged by this feature.
    @Test
    fun `groupedByUrgency still forces a completed task into Calm regardless of its remaining time`() {
        val completedButWouldBeOverdue = TaskUiModel(
            task = Task(id = 1, title = "Done", completedAt = 1_000L),
            remainingMillis = 0L,
            isTimedOut = true, // would be Overdue if completion were ignored
        )
        val genuinelyCalm = uiModel(2, "Calm", remainingMillis = soonThresholdMillis + 1, isTimedOut = false)

        val sections = listOf(completedButWouldBeOverdue, genuinelyCalm).groupedByUrgency()

        assertEquals(listOf(UrgencyLevel.Calm), sections.map { (it.key as TaskGroupKey.ByUrgency).level })
        assertEquals(setOf(completedButWouldBeOverdue, genuinelyCalm), sections.single().tasks.toSet())
    }

    // Full sort precedence stack (D3) - fixtures where priority and urgency deliberately DISAGREE ---
    // (Risk R8: fixtures where they happen to coincide "prove nothing about precedence").

    @Test
    fun `sorting by Priority ignores an imminent deadline on a lower-priority task`() {
        // Deliberately contradicting fixtures (R8): urgentButLowPriority is overdue/imminent but
        // NONE priority; calmButHighPriority is far off but HIGH priority. Sorting by Priority
        // must put the HIGH task first despite it being the less time-urgent one.
        val urgentButLowPriority = uiModel(1, "Urgent, low prio", priority = Priority.NONE, deadline = 1_000L)
        val calmButHighPriority = uiModel(2, "Calm, high prio", priority = Priority.HIGH, deadline = 100_000_000L)

        val result = listOf(urgentButLowPriority, calmButHighPriority)
            .sortedBy(TaskSortField.Priority, SortDirection.Ascending)

        assertEquals(listOf(calmButHighPriority, urgentButLowPriority), result)
    }

    @Test
    fun `sorting by Deadline ignores priority entirely - a NONE task due sooner still leads a HIGH task`() {
        // The other deliberate non-decision in D3: priority is never a hidden secondary key when
        // the selected field is Deadline.
        val soonButNoPriority = uiModel(1, "Soon, no prio", priority = Priority.NONE, deadline = 1_000L)
        val laterButHighPriority = uiModel(2, "Later, high prio", priority = Priority.HIGH, deadline = 2_000L)

        val result = listOf(laterButHighPriority, soonButNoPriority)
            .sortedBy(TaskSortField.Deadline, SortDirection.Ascending)

        assertEquals(listOf(soonButNoPriority, laterButHighPriority), result)
    }

    @Test
    fun `full precedence stack - completed always last, even a completed HIGH task sorted by Priority`() {
        val completedHigh = uiModel(1, "Completed high", priority = Priority.HIGH, completedAt = 5_000L)
        val pendingNone = uiModel(2, "Pending none", priority = Priority.NONE)

        val result = listOf(completedHigh, pendingNone).sortedBy(TaskSortField.Priority, SortDirection.Ascending)

        assertEquals(listOf(pendingNone, completedHigh), result)
    }

    @Test
    fun `full precedence stack - within equal priority, soonest deadline (nulls last) breaks the tie`() {
        val samePriorityNoDeadline = uiModel(1, "No deadline", priority = Priority.HIGH, deadline = null)
        val samePriorityWithDeadline = uiModel(2, "Has deadline", priority = Priority.HIGH, deadline = 1_000L)

        val result = listOf(samePriorityNoDeadline, samePriorityWithDeadline)
            .sortedBy(TaskSortField.Priority, SortDirection.Ascending)

        assertEquals(listOf(samePriorityWithDeadline, samePriorityNoDeadline), result)
    }

    @Test
    fun `full precedence stack - equal priority AND equal deadline falls through to title A to Z`() {
        val zeta = uiModel(1, "Zeta", priority = Priority.MEDIUM, deadline = 1_000L)
        val alfa = uiModel(2, "Alfa", priority = Priority.MEDIUM, deadline = 1_000L)

        val result = listOf(zeta, alfa).sortedBy(TaskSortField.Priority, SortDirection.Ascending)

        assertEquals(listOf(alfa, zeta), result)
    }

    @Test
    fun `total order - two fixtures equal on every tiebreak key never reorder between calls`() {
        val first = uiModel(1, "Tarea", priority = Priority.HIGH, deadline = 1_000L)
        val second = uiModel(2, "TAREA", priority = Priority.HIGH, deadline = 1_000L) // same title, case-insensitively

        val resultOne = listOf(first, second).sortedBy(TaskSortField.Priority, SortDirection.Ascending)
        val resultTwo = listOf(first, second).sortedBy(TaskSortField.Priority, SortDirection.Ascending)

        // Genuinely interchangeable at this point - stable sort keeps original relative order,
        // and repeated calls on the same input must agree with each other.
        assertEquals(listOf(first, second), resultOne)
        assertEquals(resultOne, resultTwo)
    }

    // filteredByPriority composed with the text query (US-2) ------------------------------------------

    @Test
    fun `filteredByPriority composes with filteredBy - both must match`() {
        val matchingBoth = uiModelWithPriority(1, "Preparar informe", Priority.HIGH)
        val matchingTextOnly = uiModelWithPriority(2, "Preparar informe", Priority.LOW)
        val matchingPriorityOnly = uiModelWithPriority(3, "Otra tarea", Priority.HIGH)

        val result = listOf(matchingBoth, matchingTextOnly, matchingPriorityOnly)
            .filteredBy("informe")
            .filteredByPriority(setOf(Priority.HIGH))

        assertEquals(listOf(matchingBoth), result)
    }

    @Test
    fun `filteredByPriority composes with an empty text query and a non-empty priority set`() {
        val high = uiModelWithPriority(1, "High", Priority.HIGH)
        val low = uiModelWithPriority(2, "Low", Priority.LOW)

        val result = listOf(high, low).filteredBy("").filteredByPriority(setOf(Priority.HIGH))

        assertEquals(listOf(high), result)
    }

    // rank ordering (D1, Risk R3) -----------------------------------------------------------------

    @Test
    fun `Priority rank orders NONE less than LOW less than MEDIUM less than HIGH`() {
        assertTrue(Priority.NONE.rank < Priority.LOW.rank)
        assertTrue(Priority.LOW.rank < Priority.MEDIUM.rank)
        assertTrue(Priority.MEDIUM.rank < Priority.HIGH.rank)
    }

    @Test
    fun `sorting Priority values by rank does not depend on enum declaration (ordinal) order`() {
        // Guards against a future regression that compares `ordinal` instead of `rank` (R3): even
        // if entries() happened to be shuffled, sorting by rank must still produce this order.
        val shuffled = listOf(Priority.MEDIUM, Priority.HIGH, Priority.NONE, Priority.LOW)

        val sortedByRank = shuffled.sortedBy { it.rank }

        assertEquals(listOf(Priority.NONE, Priority.LOW, Priority.MEDIUM, Priority.HIGH), sortedByRank)
    }
}
