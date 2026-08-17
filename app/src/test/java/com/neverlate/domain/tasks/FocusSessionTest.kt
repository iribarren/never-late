package com.neverlate.domain.tasks

import com.neverlate.data.tasks.Task
import com.neverlate.ui.tasks.TaskUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for `FocusSession.kt` (Modo Foco, `docs/specs/2026-08-18-focus-mode.md`):
 * [focusRosterFor] (AC-1), [focusRowsFor] (AC-2, AC-4), [focusProgressFor] (AC-3, including the
 * "vanished counts as done" rule, D1), and [isFocusSessionActive] (AC-5, D7's 12h boundary).
 */
class FocusSessionTest {

    private fun task(id: Long, title: String = "Task $id", deadline: Long? = null, completedAt: Long? = null): Task =
        Task(id = id, title = title, deadline = deadline, completedAt = completedAt)

    private fun uiModel(
        id: Long,
        title: String = "Task $id",
        deadline: Long? = null,
        remainingMillis: Long = 0L,
        isTimedOut: Boolean = false,
        completedAt: Long? = null,
    ): TaskUiModel = TaskUiModel(
        task = Task(id = id, title = title, deadline = deadline, completedAt = completedAt),
        remainingMillis = remainingMillis,
        isTimedOut = isTimedOut,
    )

    // focusRosterFor (AC-1) -----------------------------------------------------------------------

    @Test
    fun `focusRosterFor returns only the ids of pending tasks`() {
        val tasks = listOf(
            task(1, completedAt = null),
            task(2, completedAt = 1_000L),
            task(3, completedAt = null),
        )

        assertEquals(setOf(1L, 3L), focusRosterFor(tasks))
    }

    @Test
    fun `focusRosterFor over an empty list yields an empty roster`() {
        assertEquals(emptySet<Long>(), focusRosterFor(emptyList()))
    }

    @Test
    fun `focusRosterFor excludes every already-completed task`() {
        val tasks = listOf(task(1, completedAt = 5_000L), task(2, completedAt = 9_000L))

        assertTrue(focusRosterFor(tasks).isEmpty())
    }

    // focusRowsFor (AC-2, AC-4) --------------------------------------------------------------------

    @Test
    fun `focusRowsFor keeps only roster members, ordered soonest deadline first`() {
        val soon = uiModel(1, deadline = 1_000L)
        val later = uiModel(2, deadline = 5_000L)
        val notOnRoster = uiModel(3, deadline = 500L)
        val uiTasks = listOf(later, soon, notOnRoster)
        val roster = setOf(1L, 2L)

        val rows = focusRowsFor(uiTasks, roster)

        assertEquals(listOf(soon, later), rows)
    }

    @Test
    fun `focusRowsFor sinks a completed roster member to the bottom`() {
        val completed = uiModel(1, deadline = 1_000L, completedAt = 2_000L)
        val pending = uiModel(2, deadline = 9_000L)
        val roster = setOf(1L, 2L)

        val rows = focusRowsFor(listOf(completed, pending), roster)

        assertEquals(listOf(pending, completed), rows)
    }

    @Test
    fun `focusRowsFor never renders a pending task that is not on the roster`() {
        // A task created (or, per D1, pulled by sync) after the roster was frozen — pending, but
        // never a candidate for display or the count.
        val arrivedLate = uiModel(9, deadline = 100L)
        val onRoster = uiModel(1, deadline = 5_000L)

        val rows = focusRowsFor(listOf(arrivedLate, onRoster), roster = setOf(1L))

        assertEquals(listOf(onRoster), rows)
    }

    @Test
    fun `focusRowsFor over an empty roster returns no rows`() {
        val uiTasks = listOf(uiModel(1), uiModel(2))

        assertTrue(focusRowsFor(uiTasks, roster = emptySet()).isEmpty())
    }

    // focusProgressFor (AC-3, D1) -------------------------------------------------------------------

    @Test
    fun `focusProgressFor counts a completed roster member as done`() {
        val uiTasks = listOf(uiModel(1, completedAt = 1_000L), uiModel(2, completedAt = null))

        val progress = focusProgressFor(uiTasks, roster = setOf(1L, 2L))

        assertEquals(FocusProgress(total = 2, done = 1), progress)
        assertFalse(progress.isComplete)
    }

    @Test
    fun `focusProgressFor counts a roster member that no longer resolves as done`() {
        // Deleted locally, or purged elsewhere and pulled by sync — D1's "done or gone" rule.
        val stillPresent = uiModel(1, completedAt = null)
        val roster = setOf(1L, 99L)

        val progress = focusProgressFor(listOf(stillPresent), roster)

        assertEquals(FocusProgress(total = 2, done = 1), progress)
    }

    @Test
    fun `focusProgressFor is complete once every roster member is done or gone`() {
        val uiTasks = listOf(uiModel(1, completedAt = 1_000L))

        val progress = focusProgressFor(uiTasks, roster = setOf(1L, 2L))

        assertTrue(progress.isComplete)
    }

    @Test
    fun `focusProgressFor over an empty roster is trivially complete`() {
        val progress = focusProgressFor(listOf(uiModel(1)), roster = emptySet())

        assertEquals(FocusProgress(total = 0, done = 0), progress)
        assertTrue(progress.isComplete)
    }

    @Test
    fun `focusProgressFor never counts a pending task outside the roster`() {
        val outsideRoster = uiModel(9, completedAt = null)

        val progress = focusProgressFor(listOf(outsideRoster), roster = emptySet())

        assertEquals(FocusProgress(total = 0, done = 0), progress)
    }

    // isFocusSessionActive (AC-5, D7) ---------------------------------------------------------------

    private val twelveHoursMillis = 12 * 60 * 60 * 1000L

    @Test
    fun `isFocusSessionActive is false for a null session`() {
        assertFalse(isFocusSessionActive(null, now = 1_000L))
    }

    @Test
    fun `isFocusSessionActive is true just before the 12 hour boundary`() {
        val session = FocusSession(startedAt = 0L, exitCode = "1234", roster = emptySet())

        assertTrue(isFocusSessionActive(session, now = twelveHoursMillis - 1L))
    }

    @Test
    fun `isFocusSessionActive is false just after the 12 hour boundary`() {
        val session = FocusSession(startedAt = 0L, exitCode = "1234", roster = emptySet())

        assertFalse(isFocusSessionActive(session, now = twelveHoursMillis + 1L))
    }

    @Test
    fun `isFocusSessionActive is true immediately at session start`() {
        val session = FocusSession(startedAt = 5_000L, exitCode = "1234", roster = emptySet())

        assertTrue(isFocusSessionActive(session, now = 5_000L))
    }
}
