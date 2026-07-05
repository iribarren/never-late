package com.neverlate.ui.notification

import com.neverlate.data.ThemeMode
import com.neverlate.data.UserPreferences
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.tasks.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory [UserPreferencesRepository] fake local to this file: unlike [FakeTaskRepository] and
 * [FakeReminderScheduler] (promoted to [ReminderTestDoubles.kt] because two different test files
 * need them), only this decorator's tests need a preferences fake, so it stays here rather than
 * being promoted for a single caller.
 */
private class FakeUserPreferencesRepository(
    initial: UserPreferences = UserPreferences(),
) : UserPreferencesRepository {

    override val userPreferences = MutableStateFlow(initial)

    override suspend fun saveOnboarding(name: String) {}

    override suspend fun saveThemeMode(mode: ThemeMode) {}

    override suspend fun saveRemindersEnabled(enabled: Boolean) {
        userPreferences.value = userPreferences.value.copy(remindersEnabled = enabled)
    }

    override suspend fun saveReminderLeadMinutes(minutes: Int) {
        userPreferences.value = userPreferences.value.copy(reminderLeadMinutes = minutes)
    }
}

class ReminderSchedulingRepositoryTest {

    private val fixedNow = 1_000_000L

    private fun repository(
        delegate: FakeTaskRepository = FakeTaskRepository(),
        scheduler: FakeReminderScheduler = FakeReminderScheduler(),
        preferences: FakeUserPreferencesRepository = FakeUserPreferencesRepository(),
    ) = ReminderSchedulingRepository(delegate, scheduler, preferences, now = { fixedNow })

    // saveTask ------------------------------------------------------------------------------------

    @Test
    fun `saveTask with a future deadline and reminders enabled cancels then schedules using the delegate-assigned id`() = runTest {
        val delegate = FakeTaskRepository() // empty: the new task starts with id = 0
        val scheduler = FakeReminderScheduler()
        val preferences = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = true, reminderLeadMinutes = 10))
        val repo = repository(delegate, scheduler, preferences)
        val deadline = fixedNow + 700_000L // 700s away; lead is 10min (600_000ms) -> reminder 100s in the future
        val newTask = Task(id = 0, title = "Nueva tarea", deadline = deadline)

        val assignedId = repo.saveTask(newTask)

        assertEquals(1L, assignedId) // first task saved into an empty fake gets id 1, never 0
        assertEquals(listOf(newTask.copy(id = assignedId)), delegate.savedTasks)
        val expectedInstant = deadline - 600_000L
        assertEquals(
            listOf(
                FakeReminderScheduler.Call.Cancelled(assignedId),
                FakeReminderScheduler.Call.Scheduled(assignedId, expectedInstant),
            ),
            scheduler.calls,
        )
    }

    @Test
    fun `saveTask with no deadline only cancels, never schedules`() = runTest {
        val existing = Task(id = 5, title = "Sin vencimiento", deadline = null)
        val delegate = FakeTaskRepository(listOf(existing))
        val scheduler = FakeReminderScheduler()
        val repo = repository(delegate, scheduler, FakeUserPreferencesRepository(UserPreferences(remindersEnabled = true)))

        repo.saveTask(existing)

        assertEquals(listOf(FakeReminderScheduler.Call.Cancelled(5)), scheduler.calls)
        assertTrue(scheduler.scheduledCalls.isEmpty())
    }

    @Test
    fun `saveTask with reminders disabled only cancels, never schedules`() = runTest {
        val existing = Task(id = 8, title = "Con vencimiento", deadline = fixedNow + 1_000_000L)
        val delegate = FakeTaskRepository(listOf(existing))
        val scheduler = FakeReminderScheduler()
        val preferences = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = false, reminderLeadMinutes = 10))
        val repo = repository(delegate, scheduler, preferences)

        repo.saveTask(existing)

        assertEquals(listOf(FakeReminderScheduler.Call.Cancelled(8)), scheduler.calls)
        assertTrue(scheduler.scheduledCalls.isEmpty())
    }

    @Test
    fun `saveTask does not schedule when the computed reminder instant already lies in the past (OQ-6)`() = runTest {
        // Deadline only 100s away, but the lead is 10 minutes: deadline - lead lands well before
        // fixedNow, even though the deadline itself is still in the future.
        val soonDeadline = fixedNow + 100_000L
        val existing = Task(id = 9, title = "Vence pronto", deadline = soonDeadline)
        val delegate = FakeTaskRepository(listOf(existing))
        val scheduler = FakeReminderScheduler()
        val preferences = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = true, reminderLeadMinutes = 10))
        val repo = repository(delegate, scheduler, preferences)

        repo.saveTask(existing)

        assertEquals(listOf(FakeReminderScheduler.Call.Cancelled(9)), scheduler.calls)
        assertTrue(scheduler.scheduledCalls.isEmpty())
    }

    @Test
    fun `saveTask schedules using the persisted lead time preference, not a hardcoded default`() = runTest {
        val deadline = fixedNow + 1_000_000L
        val existing = Task(id = 3, title = "Con lead personalizado", deadline = deadline)
        val delegate = FakeTaskRepository(listOf(existing))
        val scheduler = FakeReminderScheduler()
        // A non-default lead time (5 min instead of the 10min default).
        val preferences = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = true, reminderLeadMinutes = 5))
        val repo = repository(delegate, scheduler, preferences)

        repo.saveTask(existing)

        val expectedInstant = deadline - 300_000L // 5 minutes in millis
        assertEquals(listOf(FakeReminderScheduler.Call.Scheduled(3, expectedInstant)), scheduler.scheduledCalls)
    }

    // deleteTask ----------------------------------------------------------------------------------

    @Test
    fun `deleteTask deletes via the delegate and cancels the reminder`() = runTest {
        val existing = Task(id = 4, title = "A borrar", deadline = fixedNow + 1_000_000L)
        val delegate = FakeTaskRepository(listOf(existing))
        val scheduler = FakeReminderScheduler()
        val repo = repository(delegate, scheduler)

        repo.deleteTask(4)

        assertEquals(listOf(4L), delegate.deletedIds)
        assertEquals(listOf(4L), scheduler.cancelledIds)
    }

    // pass-throughs ---------------------------------------------------------------------------------

    @Test
    fun `startTimer forwards to the delegate`() = runTest {
        val delegate = FakeTaskRepository(listOf(Task(id = 1, title = "T")))
        val repo = repository(delegate)

        repo.startTimer(1)

        assertEquals(listOf(1L), delegate.startedIds)
    }

    @Test
    fun `pauseTimer forwards to the delegate`() = runTest {
        val delegate = FakeTaskRepository(listOf(Task(id = 1, title = "T")))
        val repo = repository(delegate)

        repo.pauseTimer(1)

        assertEquals(listOf(1L), delegate.pausedIds)
    }

    @Test
    fun `observeTasks forwards to the delegate's tasks`() = runTest {
        val tasks = listOf(Task(id = 1, title = "A"), Task(id = 2, title = "B"))
        val delegate = FakeTaskRepository(tasks)
        val repo = repository(delegate)

        assertEquals(tasks, repo.observeTasks().first())
    }

    @Test
    fun `observeTask forwards to the delegate's single task lookup`() = runTest {
        val tasks = listOf(Task(id = 1, title = "A"), Task(id = 2, title = "B"))
        val delegate = FakeTaskRepository(tasks)
        val repo = repository(delegate)

        assertEquals(tasks[1], repo.observeTask(2).first())
    }
}
