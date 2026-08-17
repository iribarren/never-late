package com.neverlate.ui.focus

import com.neverlate.data.UserPreferences
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.domain.tasks.FocusSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** In-memory [TaskRepository] fake, minimal enough for [FocusViewModel]'s needs: records every
 *  [saveTask] call so a test can assert [FocusViewModel.toggleComplete] (D10/AC-25) went through
 *  exactly this one write path, with no focus-specific alternative. */
private class FakeTaskRepository(initialTasks: List<Task> = emptyList()) : TaskRepository {
    private val tasksFlow = MutableStateFlow(initialTasks)

    /** Every [Task] this fake has been asked to [saveTask], in call order. */
    val savedTasks = mutableListOf<Task>()

    override fun observeTasks(): Flow<List<Task>> = tasksFlow

    override fun observeTask(id: Long): Flow<Task?> = tasksFlow.map { tasks -> tasks.firstOrNull { it.id == id } }

    override suspend fun saveTask(task: Task): Long {
        savedTasks.add(task)
        tasksFlow.update { tasks -> tasks.map { if (it.id == task.id) task else it } }
        return task.id
    }

    override suspend fun deleteTask(id: Long) {
        tasksFlow.update { tasks -> tasks.filterNot { it.id == id } }
    }

    override suspend fun startTimer(id: Long) = Unit
    override suspend fun pauseTimer(id: Long) = Unit
}

/** In-memory [UserPreferencesRepository] fake, same "mutate the backing MutableStateFlow" pattern
 *  every other fake in this codebase uses for [startFocusSession]/[endFocusSession]. */
private class FakeUserPreferencesRepository(initial: UserPreferences = UserPreferences()) : UserPreferencesRepository {
    override val userPreferences = MutableStateFlow(initial)

    override suspend fun saveOnboarding(name: String) = Unit
    override suspend fun saveName(name: String) = Unit
    override suspend fun saveThemeMode(mode: com.neverlate.data.ThemeMode) = Unit
    override suspend fun saveRemindersEnabled(enabled: Boolean) = Unit
    override suspend fun saveReminderLeadMinutes(minutes: Int) = Unit
    override suspend fun saveSyncCursor(cursor: Long) = Unit
    override suspend fun saveDynamicColor(enabled: Boolean) = Unit
    override suspend fun saveTaskListArrangement(criteria: com.neverlate.domain.tasks.TaskListCriteria) = Unit

    override suspend fun startFocusSession(session: FocusSession) {
        userPreferences.value = userPreferences.value.copy(focusSession = session)
    }

    override suspend fun endFocusSession() {
        userPreferences.value = userPreferences.value.copy(focusSession = null)
    }
}

private val pendingTask = Task(id = 1, title = "Preparar la presentación", deadline = 10_000L)
private val secondPendingTask = Task(id = 2, title = "Enviar el informe", deadline = 20_000L)

/**
 * JVM tests for [FocusViewModel] (Modo Foco, `docs/specs/2026-08-18-focus-mode.md`): the exit gate
 * (AC-19/AC-20), the two exit paths (AC-21/AC-23, D3), task completion's single write path
 * (AC-25, D10), and fail-open reads of the stored session (AC-8, D6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** [FocusViewModel.uiState] is a `combine(...).stateIn(WhileSubscribed(5_000), Loading)`
     *  chain, same shape as [com.neverlate.ui.tasks.TasksViewModel.uiState] — every test needs a
     *  collector attached before its value settles past the [FocusUiState.Loading] seed. */
    private fun TestScope.collectUiState(viewModel: FocusViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
    }

    private fun TestScope.collectExitEvents(viewModel: FocusViewModel): MutableList<FocusExitEvent> {
        val events = mutableListOf<FocusExitEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.exitEvents.collect { events.add(it) }
        }
        return events
    }

    // buildUiState's `now` defaults to a real System.currentTimeMillis() read (not injected), so
    // every session below is anchored to the real wall clock rather than an arbitrary small
    // epoch-relative value — otherwise isFocusSessionActive (D7's 12h window) would already treat
    // it as expired before the test even runs its first assertion.
    private fun sessionWith(
        exitCode: String = "1234",
        roster: Set<Long> = setOf(pendingTask.id),
        startedAt: Long = System.currentTimeMillis(),
    ) = FocusSession(startedAt = startedAt, exitCode = exitCode, roster = roster)

    @Test
    fun `the exit gate opens once tasks and code are satisfied, tasks first`() = runTest {
        val taskRepository = FakeTaskRepository(listOf(pendingTask))
        val userPreferencesRepository = FakeUserPreferencesRepository(
            UserPreferences(focusSession = sessionWith(exitCode = "1234", roster = setOf(pendingTask.id))),
        )
        val viewModel = FocusViewModel(taskRepository, userPreferencesRepository)
        collectUiState(viewModel)
        advanceUntilIdle()

        // Tasks satisfied first: complete the only roster task.
        viewModel.toggleComplete(pendingTask, now = 5_000L)
        advanceUntilIdle()
        var state = viewModel.uiState.value as FocusUiState.Content
        assertTrue(state.exitPanel.tasksSatisfied)
        assertFalse(state.exitPanel.slideEnabled)

        // Then the code.
        viewModel.onCodeChange("1234")
        advanceUntilIdle()
        state = viewModel.uiState.value as FocusUiState.Content
        assertTrue(state.exitPanel.codeSatisfied)
        assertTrue(state.exitPanel.slideEnabled)
    }

    @Test
    fun `the exit gate opens once tasks and code are satisfied, code first`() = runTest {
        val taskRepository = FakeTaskRepository(listOf(pendingTask))
        val userPreferencesRepository = FakeUserPreferencesRepository(
            UserPreferences(focusSession = sessionWith(exitCode = "1234", roster = setOf(pendingTask.id))),
        )
        val viewModel = FocusViewModel(taskRepository, userPreferencesRepository)
        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onCodeChange("1234")
        advanceUntilIdle()
        var state = viewModel.uiState.value as FocusUiState.Content
        assertTrue(state.exitPanel.codeSatisfied)
        assertFalse(state.exitPanel.slideEnabled)

        viewModel.toggleComplete(pendingTask, now = 5_000L)
        advanceUntilIdle()
        state = viewModel.uiState.value as FocusUiState.Content
        assertTrue(state.exitPanel.tasksSatisfied)
        assertTrue(state.exitPanel.slideEnabled)
    }

    @Test
    fun `a wrong code leaves the gate closed and produces no penalty state`() = runTest {
        val taskRepository = FakeTaskRepository(emptyList())
        val userPreferencesRepository = FakeUserPreferencesRepository(
            UserPreferences(focusSession = sessionWith(exitCode = "1234", roster = emptySet())),
        )
        val viewModel = FocusViewModel(taskRepository, userPreferencesRepository)
        collectUiState(viewModel)
        val exitEvents = collectExitEvents(viewModel)
        advanceUntilIdle()

        // Roster is empty, so tasksSatisfied is already true — only the code gates the slide here.
        viewModel.onCodeChange("0000")
        advanceUntilIdle()
        var state = viewModel.uiState.value as FocusUiState.Content
        assertFalse(state.exitPanel.codeSatisfied)
        assertFalse(state.exitPanel.slideEnabled)

        // A wrong code never enables the slide, so completing it must be a no-op — no lockout, no
        // attempt counter exists anywhere in this ViewModel to begin with (D2/D3).
        viewModel.onSlideComplete()
        advanceUntilIdle()
        assertTrue("a wrong code must never end the session", exitEvents.isEmpty())

        // Trying again with a different wrong code produces the exact same closed gate — no state
        // remembers the previous attempt.
        viewModel.onCodeChange("9999")
        advanceUntilIdle()
        state = viewModel.uiState.value as FocusUiState.Content
        assertFalse(state.exitPanel.codeSatisfied)
    }

    @Test
    fun `completing the slide while the gate is open ends the session as completed`() = runTest {
        val taskRepository = FakeTaskRepository(listOf(pendingTask, secondPendingTask))
        val userPreferencesRepository = FakeUserPreferencesRepository(
            UserPreferences(
                focusSession = sessionWith(exitCode = "1234", roster = setOf(pendingTask.id, secondPendingTask.id)),
            ),
        )
        val viewModel = FocusViewModel(taskRepository, userPreferencesRepository)
        collectUiState(viewModel)
        val exitEvents = collectExitEvents(viewModel)
        advanceUntilIdle()

        viewModel.toggleComplete(pendingTask, now = 2_000L)
        viewModel.toggleComplete(secondPendingTask, now = 3_000L)
        viewModel.onCodeChange("1234")
        advanceUntilIdle()

        val state = viewModel.uiState.value as FocusUiState.Content
        assertTrue(state.exitPanel.slideEnabled)

        viewModel.onSlideComplete()
        advanceUntilIdle()

        assertEquals(listOf(FocusExitEvent.Completed(completedCount = 2)), exitEvents)
        assertNull(
            "endFocusSession must clear the stored session",
            userPreferencesRepository.userPreferences.value.focusSession,
        )
    }

    @Test
    fun `abandon ends the session and modifies no task`() = runTest {
        val taskRepository = FakeTaskRepository(listOf(pendingTask))
        val userPreferencesRepository = FakeUserPreferencesRepository(
            UserPreferences(focusSession = sessionWith(exitCode = "1234", roster = setOf(pendingTask.id))),
        )
        val viewModel = FocusViewModel(taskRepository, userPreferencesRepository)
        collectUiState(viewModel)
        val exitEvents = collectExitEvents(viewModel)
        advanceUntilIdle()

        // Deliberately does NOT satisfy the ritual gate first — abandon must work regardless (D3).
        viewModel.onAbandonClick()
        advanceUntilIdle()
        assertTrue((viewModel.uiState.value as FocusUiState.Content).exitPanel.showAbandonConfirm)

        viewModel.onAbandonConfirm()
        advanceUntilIdle()

        assertEquals(listOf(FocusExitEvent.Abandoned), exitEvents)
        assertNull(userPreferencesRepository.userPreferences.value.focusSession)
        assertTrue("abandoning must never write any task", taskRepository.savedTasks.isEmpty())
    }

    @Test
    fun `toggleComplete calls saveTask with completedAt set to the given instant`() = runTest {
        val taskRepository = FakeTaskRepository(listOf(pendingTask))
        val userPreferencesRepository = FakeUserPreferencesRepository(
            UserPreferences(focusSession = sessionWith(roster = setOf(pendingTask.id))),
        )
        val viewModel = FocusViewModel(taskRepository, userPreferencesRepository)

        viewModel.toggleComplete(pendingTask, now = 42_000L)
        advanceUntilIdle()

        assertEquals(1, taskRepository.savedTasks.size)
        assertEquals(42_000L, taskRepository.savedTasks.single().completedAt)
    }

    @Test
    fun `a blank stored code satisfies the code step and hides the field`() = runTest {
        val taskRepository = FakeTaskRepository(emptyList())
        val userPreferencesRepository = FakeUserPreferencesRepository(
            UserPreferences(focusSession = sessionWith(exitCode = "", roster = emptySet())),
        )
        val viewModel = FocusViewModel(taskRepository, userPreferencesRepository)
        collectUiState(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value as FocusUiState.Content
        assertFalse(state.exitPanel.codeFieldVisible)
        assertTrue(state.exitPanel.codeSatisfied)
        // Roster is also empty here, so both gates are open with zero user input at all.
        assertTrue(state.exitPanel.slideEnabled)
    }

    @Test
    fun `an expired stored session yields no session`() = runTest {
        val twelveHoursMillis = 12 * 60 * 60 * 1000L
        val taskRepository = FakeTaskRepository(listOf(pendingTask))
        val userPreferencesRepository = FakeUserPreferencesRepository(
            UserPreferences(focusSession = sessionWith(startedAt = 0L, roster = setOf(pendingTask.id))),
        )
        val viewModel = FocusViewModel(taskRepository, userPreferencesRepository)
        collectUiState(viewModel)
        advanceUntilIdle()

        // buildUiState defaults `now` to System.currentTimeMillis() (real wall-clock, always well
        // past a session started at epoch 0L) — no fake clock needed to prove this boundary.
        assertTrue(twelveHoursMillis < System.currentTimeMillis())
        assertEquals(FocusUiState.Loading, viewModel.uiState.value)
    }
}
