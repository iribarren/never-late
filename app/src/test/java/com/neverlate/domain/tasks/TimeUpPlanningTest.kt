package com.neverlate.domain.tasks

import com.neverlate.data.tasks.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive coverage of [timeUpInstantFor] and [timeUpAlertsToSchedule] against D6's full state
 * table (times-up-alert feature spec) — every row, plus the boundaries D3 and D7 call out
 * explicitly (timerEndsAt/deadline ordering, and the exact `== now` retroactive cutoff).
 */
class TimeUpPlanningTest {

    // timeUpInstantFor — D6 state table -------------------------------------------------------------

    @Test
    fun `running timer with no deadline yields timerEndsAt`() {
        val task = Task(title = "Enfoque 25 min", timerEndsAt = 500_000L, deadline = null)

        assertEquals(500_000L, timeUpInstantFor(task))
    }

    @Test
    fun `running timer and deadline where timerEndsAt is earlier yields timerEndsAt (min, D3)`() {
        val task = Task(title = "Con ambos", timerEndsAt = 400_000L, deadline = 900_000L)

        assertEquals(400_000L, timeUpInstantFor(task))
    }

    @Test
    fun `running timer and deadline where deadline is earlier yields deadline (paused-then-resumed case, D3)`() {
        // The scenario D3's rationale calls out: a paused-then-resumed task's frozen
        // remainingMillis pushed timerEndsAt past the deadline. The deadline is the earlier, truer
        // commitment and must win.
        val task = Task(title = "Retomada tras pausa", timerEndsAt = 900_000L, deadline = 400_000L)

        assertEquals(400_000L, timeUpInstantFor(task))
    }

    @Test
    fun `running timer and deadline exactly equal yields that shared instant`() {
        val task = Task(title = "Coinciden", timerEndsAt = 600_000L, deadline = 600_000L)

        assertEquals(600_000L, timeUpInstantFor(task))
    }

    @Test
    fun `paused with a deadline and no running timer yields the deadline`() {
        val task = Task(title = "Pausada con vencimiento", timerEndsAt = null, remainingMillis = 120_000L, deadline = 700_000L)

        assertEquals(700_000L, timeUpInstantFor(task))
    }

    @Test
    fun `never started with a deadline and no running timer yields the deadline`() {
        val task = Task(title = "Nunca iniciada con vencimiento", timerEndsAt = null, deadline = 700_000L)

        assertEquals(700_000L, timeUpInstantFor(task))
    }

    @Test
    fun `paused duration-only task with no deadline yields null`() {
        val task = Task(
            title = "Pausada sin vencimiento",
            timerEndsAt = null,
            remainingMillis = 120_000L,
            deadline = null,
            estimatedDurationMillis = 1_500_000L,
        )

        assertNull(timeUpInstantFor(task))
    }

    @Test
    fun `never started duration-only task with no deadline yields null`() {
        val task = Task(
            title = "Nunca iniciada sin vencimiento",
            timerEndsAt = null,
            deadline = null,
            estimatedDurationMillis = 1_500_000L,
        )

        assertNull(timeUpInstantFor(task))
    }

    // completed / deleted — must be null in every state ----------------------------------------------

    @Test
    fun `completed task with a running timer and no deadline yields null`() {
        val task = Task(title = "Completada", timerEndsAt = 500_000L, completedAt = 100_000L)

        assertNull(timeUpInstantFor(task))
    }

    @Test
    fun `completed task with a running timer and a deadline yields null`() {
        val task = Task(title = "Completada con ambos", timerEndsAt = 400_000L, deadline = 900_000L, completedAt = 100_000L)

        assertNull(timeUpInstantFor(task))
    }

    @Test
    fun `completed task paused with a deadline yields null`() {
        val task = Task(title = "Completada pausada", timerEndsAt = null, deadline = 700_000L, completedAt = 100_000L)

        assertNull(timeUpInstantFor(task))
    }

    @Test
    fun `completed duration-only task yields null`() {
        val task = Task(title = "Completada sin vencimiento", estimatedDurationMillis = 1_500_000L, completedAt = 100_000L)

        assertNull(timeUpInstantFor(task))
    }

    @Test
    fun `deleted task with a running timer and no deadline yields null`() {
        val task = Task(title = "Borrada", timerEndsAt = 500_000L, deleted = true)

        assertNull(timeUpInstantFor(task))
    }

    @Test
    fun `deleted task with a running timer and a deadline yields null`() {
        val task = Task(title = "Borrada con ambos", timerEndsAt = 400_000L, deadline = 900_000L, deleted = true)

        assertNull(timeUpInstantFor(task))
    }

    @Test
    fun `deleted task paused with a deadline yields null`() {
        val task = Task(title = "Borrada pausada", timerEndsAt = null, deadline = 700_000L, deleted = true)

        assertNull(timeUpInstantFor(task))
    }

    @Test
    fun `deleted duration-only task yields null`() {
        val task = Task(title = "Borrada sin vencimiento", estimatedDurationMillis = 1_500_000L, deleted = true)

        assertNull(timeUpInstantFor(task))
    }

    @Test
    fun `deleted and completed at once yields null`() {
        val task = Task(title = "Borrada y completada", timerEndsAt = 500_000L, deadline = 400_000L, completedAt = 50_000L, deleted = true)

        assertNull(timeUpInstantFor(task))
    }

    @Test
    fun `no timer, no deadline, no duration yields null`() {
        val task = Task(title = "Vacía")

        assertNull(timeUpInstantFor(task))
    }

    // timeUpAlertsToSchedule -------------------------------------------------------------------------

    @Test
    fun `timeUpAlertsToSchedule returns an empty list for an empty task list`() {
        assertEquals(emptyList<ReminderPlan>(), timeUpAlertsToSchedule(emptyList(), now = 1_000_000L))
    }

    @Test
    fun `timeUpAlertsToSchedule excludes an instant that already equals now exactly (D7, strict boundary)`() {
        val now = 1_000_000L
        val task = Task(id = 1, title = "Justo ahora", timerEndsAt = now)

        val result = timeUpAlertsToSchedule(listOf(task), now = now)

        assertEquals(emptyList<ReminderPlan>(), result)
    }

    @Test
    fun `timeUpAlertsToSchedule excludes an instant clearly in the past (D7)`() {
        val now = 1_000_000L
        val task = Task(id = 1, title = "Ya pasada", timerEndsAt = now - 1L)

        val result = timeUpAlertsToSchedule(listOf(task), now = now)

        assertEquals(emptyList<ReminderPlan>(), result)
    }

    @Test
    fun `timeUpAlertsToSchedule includes an instant one millisecond in the future`() {
        val now = 1_000_000L
        val task = Task(id = 1, title = "A un milisegundo", timerEndsAt = now + 1L)

        val result = timeUpAlertsToSchedule(listOf(task), now = now)

        assertEquals(listOf(ReminderPlan(taskId = 1, triggerAtMillis = now + 1L)), result)
    }

    @Test
    fun `timeUpAlertsToSchedule over a mixed list keeps only the eligible tasks, each with its own instant`() {
        val now = 1_000_000L

        val runningNoDeadline = Task(id = 1, title = "Con temporizador", timerEndsAt = now + 500_000L)
        val runningWithLaterDeadline = Task(id = 2, title = "Temporizador y vencimiento posterior", timerEndsAt = now + 100_000L, deadline = now + 900_000L)
        val pausedWithDeadline = Task(id = 3, title = "Pausada con vencimiento", timerEndsAt = null, remainingMillis = 10_000L, deadline = now + 300_000L)
        val pausedDurationOnly = Task(id = 4, title = "Pausada sin vencimiento", timerEndsAt = null, estimatedDurationMillis = 1_500_000L)
        val completed = Task(id = 5, title = "Completada", timerEndsAt = now + 500_000L, completedAt = now - 1_000L)
        val deleted = Task(id = 6, title = "Borrada", deadline = now + 500_000L, deleted = true)
        val retroactive = Task(id = 7, title = "Ya vencida", deadline = now - 1L)

        val result = timeUpAlertsToSchedule(
            listOf(runningNoDeadline, runningWithLaterDeadline, pausedWithDeadline, pausedDurationOnly, completed, deleted, retroactive),
            now = now,
        )

        assertEquals(
            listOf(
                ReminderPlan(taskId = 1, triggerAtMillis = now + 500_000L),
                ReminderPlan(taskId = 2, triggerAtMillis = now + 100_000L),
                ReminderPlan(taskId = 3, triggerAtMillis = now + 300_000L),
            ),
            result,
        )
    }

    @Test
    fun `timeUpAlertsToSchedule preserves task order for multiple qualifying tasks`() {
        val now = 0L
        val first = Task(id = 10, title = "Primera", timerEndsAt = 10_000L)
        val second = Task(id = 20, title = "Segunda", timerEndsAt = 20_000L)

        val result = timeUpAlertsToSchedule(listOf(first, second), now = now)

        assertEquals(
            listOf(ReminderPlan(10, 10_000L), ReminderPlan(20, 20_000L)),
            result,
        )
        assertTrue(result.map { it.taskId } == listOf(10L, 20L))
    }
}
