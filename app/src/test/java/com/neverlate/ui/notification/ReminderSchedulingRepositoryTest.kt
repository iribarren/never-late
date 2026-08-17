package com.neverlate.ui.notification

import com.neverlate.data.ThemeMode
import com.neverlate.data.UserPreferences
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.tasks.Task
import com.neverlate.domain.tasks.TaskListCriteria
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

    override suspend fun saveName(name: String) {
        userPreferences.value = userPreferences.value.copy(name = name.trim())
    }

    override suspend fun saveThemeMode(mode: ThemeMode) {}

    override suspend fun saveRemindersEnabled(enabled: Boolean) {
        userPreferences.value = userPreferences.value.copy(remindersEnabled = enabled)
    }

    override suspend fun saveReminderLeadMinutes(minutes: Int) {
        userPreferences.value = userPreferences.value.copy(reminderLeadMinutes = minutes)
    }

    override suspend fun saveSyncCursor(cursor: Long) {
        userPreferences.value = userPreferences.value.copy(syncCursor = cursor)
    }

    override suspend fun saveDynamicColor(enabled: Boolean) {
        userPreferences.value = userPreferences.value.copy(dynamicColor = enabled)
    }

    override suspend fun saveTaskListArrangement(criteria: TaskListCriteria) {
        userPreferences.value = userPreferences.value.copy(taskListArrangement = criteria)
    }
}

/**
 * These tests focus on the pre-existing [ReminderKind.LEAD_TIME] behaviour (filtering [Call]s down
 * to that kind), since a task with no [Task.timerEndsAt] and no [Task.deadline] produces no
 * `TIME_UP` alarm anyway in most of the fixtures below. Exhaustive `TIME_UP`/decorator coverage is
 * added by the qa-engineer pass for the times-up-alert feature.
 */
class ReminderSchedulingRepositoryTest {

    private val fixedNow = 1_000_000L

    private fun repository(
        delegate: FakeTaskRepository = FakeTaskRepository(),
        scheduler: FakeReminderScheduler = FakeReminderScheduler(),
        preferences: FakeUserPreferencesRepository = FakeUserPreferencesRepository(),
    ) = ReminderSchedulingRepository(delegate, scheduler, preferences, now = { fixedNow })

    private fun FakeReminderScheduler.leadTimeCalls() =
        calls.filter { it.kind == ReminderKind.LEAD_TIME }

    private fun FakeReminderScheduler.timeUpCalls() =
        calls.filter { it.kind == ReminderKind.TIME_UP }

    // saveTask — TIME_UP (times-up-alert feature) --------------------------------------------------

    @Test
    fun `saveTask with a running timer and no deadline schedules TIME_UP at timerEndsAt`() = runTest {
        val delegate = FakeTaskRepository()
        val scheduler = FakeReminderScheduler()
        val preferences = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = true))
        val repo = repository(delegate, scheduler, preferences)
        val timerEndsAt = fixedNow + 300_000L
        val newTask = Task(id = 0, title = "Con temporizador", timerEndsAt = timerEndsAt)

        val assignedId = repo.saveTask(newTask)

        assertEquals(
            listOf(
                FakeReminderScheduler.Call.Cancelled(assignedId, ReminderKind.TIME_UP),
                FakeReminderScheduler.Call.Scheduled(assignedId, ReminderKind.TIME_UP, timerEndsAt),
            ),
            scheduler.timeUpCalls(),
        )
    }

    @Test
    fun `saveTask with reminders disabled cancels TIME_UP but never schedules it`() = runTest {
        val existing = Task(id = 6, title = "Con temporizador", timerEndsAt = fixedNow + 300_000L)
        val delegate = FakeTaskRepository(listOf(existing))
        val scheduler = FakeReminderScheduler()
        val preferences = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = false))
        val repo = repository(delegate, scheduler, preferences)

        repo.saveTask(existing)

        assertEquals(listOf(FakeReminderScheduler.Call.Cancelled(6, ReminderKind.TIME_UP)), scheduler.timeUpCalls())
    }

    @Test
    fun `saveTask never schedules TIME_UP for a completed task, even with a running timer (D9)`() = runTest {
        val existing = Task(id = 11, title = "Completada", timerEndsAt = fixedNow + 300_000L, completedAt = fixedNow - 1_000L)
        val delegate = FakeTaskRepository(listOf(existing))
        val scheduler = FakeReminderScheduler()
        val preferences = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = true))
        val repo = repository(delegate, scheduler, preferences)

        repo.saveTask(existing)

        assertEquals(listOf(FakeReminderScheduler.Call.Cancelled(11, ReminderKind.TIME_UP)), scheduler.timeUpCalls())
        assertTrue(scheduler.scheduledCalls.none { it.kind == ReminderKind.TIME_UP })
    }

    @Test
    fun `saveTask never schedules TIME_UP for a deleted task, even with a running timer (D9)`() = runTest {
        val existing = Task(id = 12, title = "Borrada", timerEndsAt = fixedNow + 300_000L, deleted = true)
        val delegate = FakeTaskRepository(listOf(existing))
        val scheduler = FakeReminderScheduler()
        val preferences = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = true))
        val repo = repository(delegate, scheduler, preferences)

        repo.saveTask(existing)

        assertEquals(listOf(FakeReminderScheduler.Call.Cancelled(12, ReminderKind.TIME_UP)), scheduler.timeUpCalls())
        assertTrue(scheduler.scheduledCalls.none { it.kind == ReminderKind.TIME_UP })
    }

    @Test
    fun `saveTask schedules the earlier of timerEndsAt and deadline for TIME_UP (D3)`() = runTest {
        val delegate = FakeTaskRepository()
        val scheduler = FakeReminderScheduler()
        val preferences = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = true))
        val repo = repository(delegate, scheduler, preferences)
        // Paused-then-resumed scenario: timerEndsAt was pushed past the deadline.
        val newTask = Task(id = 0, title = "Retomada", timerEndsAt = fixedNow + 900_000L, deadline = fixedNow + 400_000L)

        val assignedId = repo.saveTask(newTask)

        assertTrue(
            scheduler.scheduledCalls.any {
                it.kind == ReminderKind.TIME_UP && it.taskId == assignedId && it.triggerAtMillis == fixedNow + 400_000L
            },
        )
    }

    @Test
    fun `saveTask reschedules both kinds independently — editing the deadline does not touch TIME_UP's own instant`() = runTest {
        // A task with both a deadline (drives LEAD_TIME) and a running timer whose end is later
        // than the deadline (so TIME_UP is anchored to the deadline too, per D3) — saving it must
        // still produce one cancel+schedule pair per kind, not a merged or skipped one.
        val existing = Task(
            id = 13,
            title = "Ambos vivos",
            deadline = fixedNow + 700_000L,
            timerEndsAt = fixedNow + 900_000L,
        )
        val delegate = FakeTaskRepository(listOf(existing))
        val scheduler = FakeReminderScheduler()
        val preferences = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = true, reminderLeadMinutes = 10))
        val repo = repository(delegate, scheduler, preferences)

        repo.saveTask(existing)

        val leadExpected = fixedNow + 700_000L - 600_000L // deadline - 10min lead
        val timeUpExpected = fixedNow + 700_000L // min(timerEndsAt, deadline) = deadline (D3)
        assertEquals(
            listOf(
                FakeReminderScheduler.Call.Cancelled(13, ReminderKind.LEAD_TIME),
                FakeReminderScheduler.Call.Scheduled(13, ReminderKind.LEAD_TIME, leadExpected),
            ),
            scheduler.leadTimeCalls(),
        )
        assertEquals(
            listOf(
                FakeReminderScheduler.Call.Cancelled(13, ReminderKind.TIME_UP),
                FakeReminderScheduler.Call.Scheduled(13, ReminderKind.TIME_UP, timeUpExpected),
            ),
            scheduler.timeUpCalls(),
        )
    }

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
                FakeReminderScheduler.Call.Cancelled(assignedId, ReminderKind.LEAD_TIME),
                FakeReminderScheduler.Call.Scheduled(assignedId, ReminderKind.LEAD_TIME, expectedInstant),
            ),
            scheduler.leadTimeCalls(),
        )
    }

    @Test
    fun `saveTask with no deadline only cancels, never schedules`() = runTest {
        val existing = Task(id = 5, title = "Sin vencimiento", deadline = null)
        val delegate = FakeTaskRepository(listOf(existing))
        val scheduler = FakeReminderScheduler()
        val repo = repository(delegate, scheduler, FakeUserPreferencesRepository(UserPreferences(remindersEnabled = true)))

        repo.saveTask(existing)

        assertEquals(listOf(FakeReminderScheduler.Call.Cancelled(5, ReminderKind.LEAD_TIME)), scheduler.leadTimeCalls())
        assertTrue(scheduler.scheduledCalls.none { it.kind == ReminderKind.LEAD_TIME })
    }

    @Test
    fun `saveTask with reminders disabled only cancels, never schedules`() = runTest {
        val existing = Task(id = 8, title = "Con vencimiento", deadline = fixedNow + 1_000_000L)
        val delegate = FakeTaskRepository(listOf(existing))
        val scheduler = FakeReminderScheduler()
        val preferences = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = false, reminderLeadMinutes = 10))
        val repo = repository(delegate, scheduler, preferences)

        repo.saveTask(existing)

        assertEquals(listOf(FakeReminderScheduler.Call.Cancelled(8, ReminderKind.LEAD_TIME)), scheduler.leadTimeCalls())
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

        assertEquals(listOf(FakeReminderScheduler.Call.Cancelled(9, ReminderKind.LEAD_TIME)), scheduler.leadTimeCalls())
        assertTrue(scheduler.scheduledCalls.none { it.kind == ReminderKind.LEAD_TIME })
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
        assertEquals(
            listOf(FakeReminderScheduler.Call.Scheduled(3, ReminderKind.LEAD_TIME, expectedInstant)),
            scheduler.scheduledCalls.filter { it.kind == ReminderKind.LEAD_TIME },
        )
    }

    // deleteTask ----------------------------------------------------------------------------------

    @Test
    fun `deleteTask deletes via the delegate and cancels both alarm kinds`() = runTest {
        val existing = Task(id = 4, title = "A borrar", deadline = fixedNow + 1_000_000L)
        val delegate = FakeTaskRepository(listOf(existing))
        val scheduler = FakeReminderScheduler()
        val repo = repository(delegate, scheduler)

        repo.deleteTask(4)

        assertEquals(listOf(4L), delegate.deletedIds)
        assertEquals(
            setOf(ReminderKind.LEAD_TIME, ReminderKind.TIME_UP),
            scheduler.cancelledCalls.filter { it.taskId == 4L }.map { it.kind }.toSet(),
        )
    }

    // un-completing (US-3's undo path) -----------------------------------------------------------

    @Test
    fun `un-completing a task via saveTask reschedules both alarms that are still in the future`() = runTest {
        val completed = Task(
            id = 14,
            title = "Deshacer completado",
            deadline = fixedNow + 700_000L,
            timerEndsAt = fixedNow + 300_000L,
            completedAt = fixedNow - 1_000L,
        )
        val delegate = FakeTaskRepository(listOf(completed))
        val scheduler = FakeReminderScheduler()
        val preferences = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = true, reminderLeadMinutes = 10))
        val repo = repository(delegate, scheduler, preferences)

        val uncompleted = completed.copy(completedAt = null)
        repo.saveTask(uncompleted)

        assertTrue(
            "un-completing must re-arm LEAD_TIME once completedAt is cleared",
            scheduler.scheduledCalls.any { it.taskId == 14L && it.kind == ReminderKind.LEAD_TIME },
        )
        assertTrue(
            "un-completing must re-arm TIME_UP once completedAt is cleared",
            scheduler.scheduledCalls.any { it.taskId == 14L && it.kind == ReminderKind.TIME_UP },
        )
    }

    // pass-throughs / TIME_UP rescheduling ---------------------------------------------------------

    @Test
    fun `startTimer forwards to the delegate and reschedules TIME_UP`() = runTest {
        val delegate = FakeTaskRepository(listOf(Task(id = 1, title = "T", estimatedDurationMillis = 500_000L)), now = { fixedNow })
        val scheduler = FakeReminderScheduler()
        val repo = repository(delegate, scheduler)

        repo.startTimer(1)

        assertEquals(listOf(1L), delegate.startedIds)
        assertTrue(scheduler.cancelledCalls.any { it.taskId == 1L && it.kind == ReminderKind.TIME_UP })
        assertTrue(scheduler.scheduledCalls.any { it.taskId == 1L && it.kind == ReminderKind.TIME_UP })
    }

    @Test
    fun `startTimer schedules TIME_UP at the actual new timerEndsAt written by the delegate`() = runTest {
        // estimatedDurationMillis = 500_000 and no deadline: startTimer should end up with
        // timerEndsAt == now + 500_000, and TIME_UP must be scheduled at exactly that instant —
        // this is the "the fake must actually mutate timerEndsAt, not just record the call" trap
        // the spec calls out.
        val delegate = FakeTaskRepository(listOf(Task(id = 1, title = "T", estimatedDurationMillis = 500_000L)), now = { fixedNow })
        val scheduler = FakeReminderScheduler()
        val repo = repository(delegate, scheduler)

        repo.startTimer(1)

        val expectedTimerEndsAt = fixedNow + 500_000L
        assertEquals(expectedTimerEndsAt, delegate.observeTask(1).first()?.timerEndsAt)
        assertTrue(
            scheduler.scheduledCalls.any {
                it.taskId == 1L && it.kind == ReminderKind.TIME_UP && it.triggerAtMillis == expectedTimerEndsAt
            },
        )
    }

    @Test
    fun `startTimer leaves the task's LEAD_TIME alarm untouched (US-5 independence)`() = runTest {
        val delegate = FakeTaskRepository(
            listOf(Task(id = 1, title = "Con vencimiento", deadline = fixedNow + 1_000_000L, estimatedDurationMillis = 500_000L)),
        )
        val scheduler = FakeReminderScheduler()
        val repo = repository(delegate, scheduler)

        repo.startTimer(1)

        assertTrue(
            "startTimer must not issue any LEAD_TIME call at all — only TIME_UP is re-anchored by a timer change",
            scheduler.calls.none { it.taskId == 1L && it.kind == ReminderKind.LEAD_TIME },
        )
    }

    @Test
    fun `pauseTimer forwards to the delegate and cancels TIME_UP`() = runTest {
        val delegate = FakeTaskRepository(listOf(Task(id = 1, title = "T", timerEndsAt = fixedNow + 500_000L)), now = { fixedNow })
        val scheduler = FakeReminderScheduler()
        val repo = repository(delegate, scheduler)

        repo.pauseTimer(1)

        assertEquals(listOf(1L), delegate.pausedIds)
        assertTrue(scheduler.cancelledCalls.any { it.taskId == 1L && it.kind == ReminderKind.TIME_UP })
        assertTrue(scheduler.scheduledCalls.none { it.taskId == 1L && it.kind == ReminderKind.TIME_UP })
    }

    @Test
    fun `pauseTimer leaves the task's LEAD_TIME alarm untouched (US-5 independence)`() = runTest {
        val delegate = FakeTaskRepository(
            listOf(Task(id = 1, title = "Con vencimiento", deadline = fixedNow + 1_000_000L, timerEndsAt = fixedNow + 500_000L)),
        )
        val scheduler = FakeReminderScheduler()
        val repo = repository(delegate, scheduler)

        repo.pauseTimer(1)

        assertTrue(
            "pauseTimer must not issue any LEAD_TIME call at all — only TIME_UP is affected by a pause",
            scheduler.calls.none { it.taskId == 1L && it.kind == ReminderKind.LEAD_TIME },
        )
    }

    @Test
    fun `pressing play again after pause reschedules TIME_UP against the new timerEndsAt (US-3)`() = runTest {
        val delegate = FakeTaskRepository(listOf(Task(id = 1, title = "T", estimatedDurationMillis = 500_000L)), now = { fixedNow })
        val scheduler = FakeReminderScheduler()
        val repo = repository(delegate, scheduler)

        repo.startTimer(1)
        repo.pauseTimer(1)
        repo.startTimer(1)

        // Cancel, schedule, cancel(only), cancel, schedule — the important assertion is that the
        // final state has a fresh TIME_UP alarm scheduled, not none and not two stacked ones.
        val finalTimerEndsAt = delegate.observeTask(1).first()?.timerEndsAt
        assertEquals(fixedNow + 500_000L, finalTimerEndsAt)
        assertEquals(
            listOf(fixedNow + 500_000L, fixedNow + 500_000L),
            scheduler.scheduledCalls.filter { it.taskId == 1L && it.kind == ReminderKind.TIME_UP }.map { it.triggerAtMillis },
        )
    }

    @Test
    fun `startTimer on a task past its estimated duration schedules no TIME_UP alarm since the resulting instant is already past`() = runTest {
        // computeRemainingMillis coerces at zero, so timerEndsAt lands exactly at "now" for a task
        // whose duration already elapsed while paused — isReminderInFuture's strict `>` then
        // excludes it (D7).
        val delegate = FakeTaskRepository(
            listOf(Task(id = 1, title = "T", remainingMillis = 0L, estimatedDurationMillis = 500_000L)),
            now = { fixedNow },
        )
        val scheduler = FakeReminderScheduler()
        val repo = repository(delegate, scheduler)

        repo.startTimer(1)

        assertTrue(scheduler.scheduledCalls.none { it.taskId == 1L && it.kind == ReminderKind.TIME_UP })
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
