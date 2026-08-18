package com.neverlate.ui.tasks

import com.neverlate.data.FakeUserPreferencesRepository
import com.neverlate.data.UserPreferences
import com.neverlate.data.settings.MotionSettings
import com.neverlate.data.tasks.Priority
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.data.tasks.computeRemainingMillis
import com.neverlate.domain.tasks.ShapedTaskList
import com.neverlate.domain.tasks.SortDirection
import com.neverlate.domain.tasks.TaskGroupAxis
import com.neverlate.domain.tasks.TaskListCriteria
import com.neverlate.domain.tasks.TaskSortField
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * In-memory fake for [TaskRepository]. [startTimer]/[pauseTimer] mirror
 * [com.neverlate.data.tasks.RoomTaskRepository]'s wall-clock-based logic (same
 * [com.neverlate.data.tasks.computeRemainingMillis] call), so [TasksViewModel] sees the same
 * shape of state transitions it would see against the real Room-backed implementation.
 */
private class FakeTaskRepository(initialTasks: List<Task> = emptyList()) : TaskRepository {

    private val tasksFlow = MutableStateFlow(initialTasks)

    /**
     * Counts calls to [observeTasks] (`reduce-motion` spec). [TasksViewModel.uiTasksFlow] builds
     * its `combine(repository.observeTasks(), motionSettings.reduceMotion)` once, in a property
     * initializer - so this call count is fixed the moment the ViewModel is constructed and stays
     * flat no matter how many times [MotionSettings.reduceMotion] flips afterwards. That makes it
     * a reliable, non-flaky way to prove the task subscription is never rebuilt on a reduce-motion
     * toggle, without depending on wall-clock-derived state actually changing between ticks.
     */
    var observeTasksCallCount: Int = 0
        private set

    override fun observeTasks(): Flow<List<Task>> {
        observeTasksCallCount++
        return tasksFlow
    }

    override fun observeTask(id: Long): Flow<Task?> = tasksFlow.map { tasks -> tasks.firstOrNull { it.id == id } }

    override suspend fun saveTask(task: Task): Long {
        val id = if (task.id == 0L) (tasksFlow.value.maxOfOrNull { it.id } ?: 0L) + 1 else task.id
        tasksFlow.update { tasks ->
            if (task.id == 0L) {
                tasks + task.copy(id = id)
            } else {
                tasks.map { if (it.id == task.id) task else it }
            }
        }
        return id
    }

    override suspend fun deleteTask(id: Long) {
        tasksFlow.update { tasks -> tasks.filterNot { it.id == id } }
    }

    override suspend fun startTimer(id: Long) {
        val now = System.currentTimeMillis()
        tasksFlow.update { tasks ->
            tasks.map { task ->
                if (task.id != id) {
                    task
                } else {
                    val remaining = computeRemainingMillis(task, now)
                    task.copy(timerEndsAt = now + remaining, remainingMillis = null)
                }
            }
        }
    }

    override suspend fun pauseTimer(id: Long) {
        val now = System.currentTimeMillis()
        tasksFlow.update { tasks ->
            tasks.map { task ->
                if (task.id != id) {
                    task
                } else {
                    val remaining = computeRemainingMillis(task, now)
                    task.copy(timerEndsAt = null, remainingMillis = remaining)
                }
            }
        }
    }
}

/**
 * In-memory fake for [MotionSettings] (`reduce-motion` spec). Defaults to `false` (full motion) so
 * every pre-existing test above is unaffected; [reduceMotionFlow] lets a test flip the value live
 * to exercise [TasksViewModel.uiTasksFlow]'s `combine` with [MotionSettings.reduceMotion].
 */
private class FakeMotionSettings(initial: Boolean = false) : MotionSettings {
    val reduceMotionFlow = MutableStateFlow(initial)
    override val reduceMotion: Flow<Boolean> = reduceMotionFlow
}

// FakeUserPreferencesRepository is the shared fake at com.neverlate.data.FakeUserPreferencesRepository
// (D12 of docs/specs/2026-08-18-focus-mode-shielding.md) — this test file only cares that
// TasksViewModel compiles/constructs against a working repository; the name-specific behaviour
// itself is covered separately.

private val teaTask = Task(id = 1, title = "Preparar té", estimatedDurationMillis = 5 * 60_000L)
private val reportTask = Task(id = 2, title = "Enviar informe", estimatedDurationMillis = 10 * 60_000L)

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // TasksViewModel.init launches on viewModelScope (Dispatchers.Main), and its ticker uses
        // delay(); StandardTestDispatcher + setMain lets each test control exactly how much of
        // that coroutine chain runs. runCurrent() (rather than advanceUntilIdle()) is used
        // whenever a task is running, because the ticker's `delay(1000)` reschedules itself
        // forever while any task keeps running - advancing virtual time to "idle" would never
        // terminate. Tests that only wait out the search debounce (no running task) can safely
        // use advanceTimeBy(...)/advanceUntilIdle() instead.
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Feature 04b: [TasksViewModel.uiState] is now `combine(...).stateIn(viewModelScope,
     * SharingStarted.WhileSubscribed(5_000), Loading)` - a **cold** Flow chain behind a **hot**
     * StateFlow that only does work while at least one collector is attached. Every test below
     * that needs `uiState` to actually settle past its `Loading` seed must launch a collector
     * first; this helper does it once, in [TestScope.backgroundScope] so it is torn down
     * automatically when the test ends, using [UnconfinedTestDispatcher] so collection starts
     * eagerly instead of waiting its turn behind [testDispatcher]'s queued work.
     */
    private fun TestScope.collectUiState(viewModel: TasksViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
    }

    /**
     * `persisted-list-preferences` (D2/D3): mirrors [collectUiState] above, but for
     * [TasksViewModel.criteria]. Unlike the pre-this-feature `_criteria` `MutableStateFlow`,
     * [TasksViewModel.criteria] is now its own `combine(arrangement, _priorityFilter).stateIn(...)`
     * chain, seeded at `null` until the fake repository's arrangement is actually collected — so
     * reading `criteria.value` right after constructing the ViewModel or right after calling a
     * setter, with no collector attached and no time advanced, is no longer valid (it would either
     * NPE on the `!!` or observe a stale value). Every test that reads [TasksViewModel.criteria]
     * must attach this collector first and call `advanceUntilIdle()` before asserting, the same
     * "wait for a value before deciding" discipline [collectUiState] already established.
     */
    private fun TestScope.collectCriteria(viewModel: TasksViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.criteria.collect {}
        }
    }

    /**
     * editable-profile-name spec (AC-9/US-3): [TasksViewModel.userName] is its own
     * `stateIn(..., SharingStarted.WhileSubscribed(5_000), "")`, mirroring [collectUiState]'s
     * "attach a collector before advancing time" precedent above.
     */
    @Test
    fun `userName reflects the initial stored name and later reactive emissions`() = runTest {
        val userPreferencesRepository = FakeUserPreferencesRepository(UserPreferences(name = "Ada"))
        val viewModel = TasksViewModel(
            motionSettings = FakeMotionSettings(),
            userPreferencesRepository = userPreferencesRepository,
            repository = FakeTaskRepository(emptyList()),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.userName.collect {}
        }
        advanceUntilIdle()

        assertEquals("Ada", viewModel.userName.value)

        // A later emission from the repository (e.g. a rename from Settings) must reach
        // TasksViewModel.userName without recreating the ViewModel — the reactive round trip
        // US-3 requires, proven directly against the StateFlow rather than the UI layer.
        userPreferencesRepository.userPreferences.update { it.copy(name = "Bea") }
        advanceUntilIdle()

        assertEquals("Bea", viewModel.userName.value)
    }

    @Test
    fun `uiState stays at its Loading seed with no collector attached`() {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask)))

        // No collector on uiState yet: the combine/debounce chain behind stateIn is inert
        // (SharingStarted.WhileSubscribed never starts it without a subscriber), so advancing the
        // scheduler changes nothing here - contrast with every test below, which attaches a
        // collector via collectUiState(...) before advancing time.
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is TasksUiState.Loading)
    }

    /** Reads the flat task list out of a [TasksUiState.Content], failing loudly if it is grouped
     *  instead — every existing test here exercises the default (ungrouped) criteria. */
    private fun TasksUiState.contentTasks(): List<TaskUiModel> {
        val shaped = (this as TasksUiState.Content).shaped
        assertTrue("expected a Flat shaped result, got $shaped", shaped is ShapedTaskList.Flat)
        return (shaped as ShapedTaskList.Flat).tasks
    }

    @Test
    fun `repository with tasks produces Content state with computed remaining time`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask, reportTask)))
        collectUiState(viewModel)

        // debounce(300) delays even the *initial* "" query - the pipeline needs the debounce to
        // settle once before uiState ever leaves its Loading seed (see collectUiState's KDoc).
        advanceTimeBy(300)
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state is TasksUiState.Content)
        val tasks = state.contentTasks()
        assertEquals(2, tasks.size)
        val tea = tasks.first { it.task.id == teaTask.id }
        assertEquals(teaTask.estimatedDurationMillis, tea.remainingMillis)
        assertFalse(tea.isTimedOut)
    }

    @Test
    fun `empty repository produces Empty state`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(emptyList()))
        collectUiState(viewModel)

        advanceTimeBy(300)
        runCurrent()

        assertTrue(viewModel.uiState.value is TasksUiState.Empty)
    }

    @Test
    fun `startTimer marks the task running and remaining stays close to its full duration`() = runTest(testDispatcher) {
        val repository = FakeTaskRepository(listOf(teaTask))
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = repository)
        collectUiState(viewModel)
        advanceTimeBy(300)
        runCurrent()

        viewModel.startTimer(teaTask.id)
        runCurrent()

        val state = viewModel.uiState.value as TasksUiState.Content
        val uiTask = state.contentTasks().single()
        assertTrue(uiTask.task.isRunning)
        assertTrue(
            "remaining (${uiTask.remainingMillis}) should stay close to the full duration " +
                "(${teaTask.estimatedDurationMillis}) right after starting",
            abs(teaTask.estimatedDurationMillis!! - uiTask.remainingMillis) < 2_000L,
        )

        // Stop the ticker before leaving the test body. While a task is running the ticker
        // reschedules its own delay(1000) forever (see setUp), and runTest drains the scheduler
        // once the body returns - so a test that ends with a task still running never terminates.
        // It hangs in *virtual* time, which is why runTest's own wall-clock timeout cannot break
        // it either: the loop never suspends in real time. Every other timer test here already
        // pauses or runs the countdown out; this one used to be the exception.
        viewModel.pauseTimer(teaTask.id)
        runCurrent()
    }

    @Test
    fun `pauseTimer stops the countdown and freezes remaining time`() = runTest(testDispatcher) {
        val repository = FakeTaskRepository(listOf(teaTask))
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = repository)
        collectUiState(viewModel)
        advanceTimeBy(300)
        runCurrent()
        viewModel.startTimer(teaTask.id)
        runCurrent()

        viewModel.pauseTimer(teaTask.id)
        runCurrent()

        val state = viewModel.uiState.value as TasksUiState.Content
        val uiTask = state.contentTasks().single()
        assertFalse(uiTask.task.isRunning)
        assertEquals(uiTask.remainingMillis, uiTask.task.remainingMillis)
    }

    @Test(timeout = 5_000)
    fun `countdown reaching zero auto-pauses the task and marks it timed out`() = runTest(testDispatcher) {
        // Already expired when observed: timerEndsAt is in the past, so the very first tick
        // computes a remaining time of zero (US-5's "no negative values" rule).
        val expiredTask = Task(id = 3, title = "Tarea vencida", timerEndsAt = 1L)
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(expiredTask)))
        collectUiState(viewModel)

        advanceTimeBy(300)
        runCurrent()

        val state = viewModel.uiState.value as TasksUiState.Content
        val uiTask = state.contentTasks().single()
        assertTrue(uiTask.isTimedOut)
        assertEquals(0L, uiTask.remainingMillis)
        assertFalse("task should auto-pause once its countdown reaches zero", uiTask.task.isRunning)
    }

    @Test
    fun `deleteTask removes the task from the list`() = runTest(testDispatcher) {
        val repository = FakeTaskRepository(listOf(teaTask))
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = repository)
        collectUiState(viewModel)
        advanceTimeBy(300)
        runCurrent()

        viewModel.deleteTask(teaTask.id)
        runCurrent()

        assertTrue(viewModel.uiState.value is TasksUiState.Empty)
    }

    // Feature 03b: sort / group criteria (immediate, no debounce) ----------------------------------

    @Test
    fun `criteria starts at its defaults - deadline ascending, ungrouped`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask)))
        collectCriteria(viewModel)
        advanceUntilIdle()

        assertEquals(TaskListCriteria(), viewModel.criteria.value)
    }

    @Test
    fun `onSortFieldChange updates criteria's sortField and keeps the current direction`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask)))
        collectCriteria(viewModel)
        advanceUntilIdle()

        // D2: the setter writes through the fake repository asynchronously (viewModelScope.launch)
        // rather than mutating a local MutableStateFlow synchronously - the new value only reaches
        // criteria once the DataStore-backed flow re-emits, hence the advanceUntilIdle() below.
        viewModel.onSortFieldChange(TaskSortField.Title)
        advanceUntilIdle()

        assertEquals(TaskSortField.Title, viewModel.criteria.value!!.sortField)
        assertEquals(SortDirection.Ascending, viewModel.criteria.value!!.direction)
    }

    @Test
    fun `onToggleSortDirection flips ascending to descending and back`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask)))
        collectCriteria(viewModel)
        advanceUntilIdle()

        viewModel.onToggleSortDirection()
        advanceUntilIdle()
        assertEquals(SortDirection.Descending, viewModel.criteria.value!!.direction)

        viewModel.onToggleSortDirection()
        advanceUntilIdle()
        assertEquals(SortDirection.Ascending, viewModel.criteria.value!!.direction)
    }

    @Test
    fun `onGroupAxisChange to Urgency produces a Grouped shaped result`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask)))
        collectUiState(viewModel)
        collectCriteria(viewModel)
        advanceTimeBy(300)
        runCurrent()

        viewModel.onGroupAxisChange(TaskGroupAxis.Urgency)
        advanceUntilIdle()

        assertEquals(TaskGroupAxis.Urgency, viewModel.criteria.value!!.groupAxis)
        val state = viewModel.uiState.value
        assertTrue(state is TasksUiState.Content)
        assertTrue((state as TasksUiState.Content).shaped is ShapedTaskList.Grouped)
    }

    @Test
    fun `onGroupAxisChange back to None returns to an ungrouped Flat result`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask)))
        collectUiState(viewModel)
        collectCriteria(viewModel)
        advanceTimeBy(300)
        runCurrent()

        viewModel.onGroupAxisChange(TaskGroupAxis.Urgency)
        advanceUntilIdle()
        viewModel.onGroupAxisChange(TaskGroupAxis.None)
        advanceUntilIdle()

        assertEquals(TaskGroupAxis.None, viewModel.criteria.value!!.groupAxis)
        val state = viewModel.uiState.value
        assertTrue(state is TasksUiState.Content)
        assertTrue((state as TasksUiState.Content).shaped is ShapedTaskList.Flat)
    }

    @Test
    fun `onGroupAxisChange to Priority produces a Grouped shaped result`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask)))
        collectUiState(viewModel)
        collectCriteria(viewModel)
        advanceTimeBy(300)
        runCurrent()

        viewModel.onGroupAxisChange(TaskGroupAxis.Priority)
        advanceUntilIdle()

        assertEquals(TaskGroupAxis.Priority, viewModel.criteria.value!!.groupAxis)
        val state = viewModel.uiState.value
        assertTrue(state is TasksUiState.Content)
        assertTrue((state as TasksUiState.Content).shaped is ShapedTaskList.Grouped)
    }

    @Test
    fun `onGroupAxisChange to Priority then Urgency deselects the previous axis, never both at once`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask)))
        collectCriteria(viewModel)
        advanceUntilIdle()

        viewModel.onGroupAxisChange(TaskGroupAxis.Priority)
        advanceUntilIdle()
        assertEquals(TaskGroupAxis.Priority, viewModel.criteria.value!!.groupAxis)

        viewModel.onGroupAxisChange(TaskGroupAxis.Urgency)
        advanceUntilIdle()

        // TaskGroupAxis is a single field, not two independent booleans, so switching to Urgency
        // is inherently exclusive - this pins that the ViewModel never tries to represent "both".
        assertEquals(TaskGroupAxis.Urgency, viewModel.criteria.value!!.groupAxis)
    }

    // Priority filter (D5, US-2 of `priority-sorting`) --------------------------------------------

    @Test
    fun `onPriorityFilterToggle adds a priority to an empty filter set`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask)))
        collectCriteria(viewModel)
        advanceUntilIdle()

        // Unlike the sort/group setters, onPriorityFilterToggle mutates _priorityFilter directly
        // (D1/D2 - the filter is never persisted), so no repository round trip is needed here; the
        // advanceUntilIdle() below is still required to let criteria's own combine/stateIn chain
        // re-emit the updated value to its collector.
        viewModel.onPriorityFilterToggle(Priority.HIGH)
        advanceUntilIdle()

        assertEquals(setOf(Priority.HIGH), viewModel.criteria.value!!.priorityFilter)
    }

    @Test
    fun `onPriorityFilterToggle twice for the same priority returns to no filter`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask)))
        collectCriteria(viewModel)
        advanceUntilIdle()

        viewModel.onPriorityFilterToggle(Priority.HIGH)
        viewModel.onPriorityFilterToggle(Priority.HIGH)
        advanceUntilIdle()

        assertEquals(emptySet<Priority>(), viewModel.criteria.value!!.priorityFilter)
    }

    @Test
    fun `onPriorityFilterToggle supports multiple priorities active at once`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask)))
        collectCriteria(viewModel)
        advanceUntilIdle()

        viewModel.onPriorityFilterToggle(Priority.HIGH)
        viewModel.onPriorityFilterToggle(Priority.MEDIUM)
        advanceUntilIdle()

        assertEquals(setOf(Priority.HIGH, Priority.MEDIUM), viewModel.criteria.value!!.priorityFilter)
    }

    @Test
    fun `a priority filter that matches nothing produces NoResults`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask))) // teaTask defaults to Priority.NONE
        collectUiState(viewModel)
        advanceTimeBy(300)
        runCurrent()

        viewModel.onPriorityFilterToggle(Priority.HIGH)
        runCurrent()

        assertTrue(viewModel.uiState.value is TasksUiState.NoResults)
    }

    @Test
    fun `onClearFilters clears both the text query and the priority filter, escaping NoResults`() =
        runTest(testDispatcher) {
            val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask)))
            collectUiState(viewModel)
            collectCriteria(viewModel)
            advanceTimeBy(300)
            runCurrent()

            // Strand the user in NoResults via BOTH filters at once (R2): a query that matches
            // nothing AND a priority filter that excludes the one task that would otherwise match.
            viewModel.onQueryChange("xyz no existe")
            viewModel.onPriorityFilterToggle(Priority.HIGH)
            advanceTimeBy(300)
            runCurrent()
            assertTrue(viewModel.uiState.value is TasksUiState.NoResults)

            viewModel.onClearFilters()
            advanceTimeBy(300)
            runCurrent()

            assertEquals("", viewModel.query.value)
            assertEquals(emptySet<Priority>(), viewModel.criteria.value!!.priorityFilter)
            assertTrue(viewModel.uiState.value is TasksUiState.Content)
        }

    // Feature 04b: reactive search - debounce, combine, distinctUntilChanged, cancellation --------

    @Test
    fun `onQueryChange updates query immediately, independent of the debounce`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask, reportTask)))
        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onQueryChange("té")

        // US-1: the field's own StateFlow updates on the spot - no debounce at the setter, even
        // though the filtered uiState below has not caught up yet.
        assertEquals("té", viewModel.query.value)
    }

    @Test
    fun `typing letter by letter only filters once after the pause, not on every keystroke`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask, reportTask)))
        collectUiState(viewModel)
        advanceUntilIdle()

        // Each keystroke lands well inside the 300ms debounce window of the previous one.
        "té".forEachIndexed { index, _ ->
            viewModel.onQueryChange("té".take(index + 1))
            advanceTimeBy(50)
        }

        // Only 100ms have passed since the last keystroke - filtering must not have applied yet.
        assertEquals("té", viewModel.query.value)
        assertEquals(2, viewModel.uiState.value.contentTasks().size)

        advanceTimeBy(300)
        runCurrent()

        val tasks = viewModel.uiState.value.contentTasks()
        assertEquals(listOf(teaTask.id), tasks.map { it.task.id })
    }

    @Test
    fun `onQueryChange that matches nothing produces NoResults, not Empty`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask, reportTask)))
        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onQueryChange("xyz no existe")
        advanceTimeBy(300)
        runCurrent()

        // NoResults (there ARE tasks, the filter just excludes all of them) is a distinct state
        // from Empty (there are no tasks at all) - see TasksUiState's KDoc, US-4.
        assertTrue(viewModel.uiState.value is TasksUiState.NoResults)
    }

    @Test
    fun `clearing the query after NoResults returns to Content once the debounce settles`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask)))
        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onQueryChange("xyz no existe")
        advanceTimeBy(300)
        runCurrent()
        assertTrue(viewModel.uiState.value is TasksUiState.NoResults)

        viewModel.onQueryChange("")
        advanceTimeBy(300)
        runCurrent()

        assertTrue(viewModel.uiState.value is TasksUiState.Content)
    }

    @Test
    fun `re-entering the same settled query does not add a second emission`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask, reportTask)))
        val emissions = mutableListOf<TasksUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { emissions.add(it) }
        }
        advanceUntilIdle()

        viewModel.onQueryChange("té")
        advanceTimeBy(300)
        runCurrent()
        val emissionCountAfterFirstSettle = emissions.size

        // Same text again - distinctUntilChanged (plus StateFlow's own conflation upstream) means
        // this must not re-trigger the filter pipeline a second time.
        viewModel.onQueryChange("té")
        advanceTimeBy(300)
        runCurrent()

        assertEquals(
            "re-submitting an unchanged, already-settled query should not add a new uiState emission",
            emissionCountAfterFirstSettle,
            emissions.size,
        )
    }

    @Test
    fun `rapid A to AB to A settles on A's filtered result, not an intermediate query`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = FakeTaskRepository(listOf(teaTask, reportTask)))
        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onQueryChange("té") // A: matches only teaTask ("Preparar té")
        advanceTimeBy(100)
        viewModel.onQueryChange("téxyz") // AB: matches nothing
        advanceTimeBy(100)
        viewModel.onQueryChange("té") // back to A, all within 300ms of the previous keystroke
        advanceTimeBy(300)
        runCurrent()

        // The debounce restarts on every new value, so neither "té" (the first time) nor "téxyz"
        // ever reached the filter - only the last, settled "té" does (US-2's "last-wins").
        val tasks = viewModel.uiState.value.contentTasks()
        assertEquals(listOf(teaTask.id), tasks.map { it.task.id })
    }

    @Test
    fun `combine re-emits when the task list changes, independent of the query`() = runTest(testDispatcher) {
        val repository = FakeTaskRepository(listOf(teaTask))
        val viewModel = TasksViewModel(motionSettings = FakeMotionSettings(), userPreferencesRepository = FakeUserPreferencesRepository(), repository = repository)
        collectUiState(viewModel)

        viewModel.onQueryChange("en") // matches "Enviar informe" but not "Preparar té"
        advanceTimeBy(300)
        runCurrent()

        // No task in the repository matches "en" yet - the query alone settles into NoResults.
        assertTrue(viewModel.uiState.value is TasksUiState.NoResults)

        // Add a matching task directly through the repository - the query never changes again.
        repository.saveTask(reportTask.copy(id = 0))
        runCurrent()

        val tasks = viewModel.uiState.value.contentTasks()
        assertEquals(listOf(reportTask.title), tasks.map { it.task.title })
    }

    // `reduce-motion` spec (D4): MotionSettings wiring ------------------------------------------

    @Test
    fun `flipping reduceMotion mid-stream never re-subscribes to the task flow`() = runTest(testDispatcher) {
        val repository = FakeTaskRepository(listOf(teaTask))
        val motionSettings = FakeMotionSettings(initial = false)
        val viewModel = TasksViewModel(
            motionSettings = motionSettings,
            repository = repository,
            userPreferencesRepository = FakeUserPreferencesRepository(),
        )

        // uiTasksFlow builds combine(repository.observeTasks(), motionSettings.reduceMotion) once,
        // in a property initializer - so the task flow is already subscribed at construction time,
        // before uiState even has a collector.
        assertEquals(1, repository.observeTasksCallCount)

        collectUiState(viewModel)
        advanceTimeBy(300)
        runCurrent()
        viewModel.startTimer(teaTask.id)
        runCurrent()
        assertTrue((viewModel.uiState.value as TasksUiState.Content).contentTasks().single().task.isRunning)

        // Flip the system setting back and forth mid-stream, the way a real toggle in
        // Ajustes -> Accesibilidad would. Only the *interval* fed into flatMapLatest's ticker
        // should be re-derived (via tickIntervalFor) - the underlying observeTasks() subscription
        // must stay the same one from construction.
        motionSettings.reduceMotionFlow.value = true
        runCurrent()
        motionSettings.reduceMotionFlow.value = false
        runCurrent()
        motionSettings.reduceMotionFlow.value = true
        runCurrent()

        assertEquals(
            "the task flow must be subscribed exactly once, regardless of how many times " +
                "reduceMotion flips",
            1,
            repository.observeTasksCallCount,
        )
        val finalTask = (viewModel.uiState.value as TasksUiState.Content).contentTasks().single()
        assertTrue(
            "the running task's state must survive every reduceMotion flip unchanged",
            finalTask.task.isRunning,
        )

        // Pause only once the assertions above have run: they need the task still running, but the
        // test body must not *end* with it running or runTest's final drain never terminates. Same
        // reason as in `startTimer marks the task running...` above.
        viewModel.pauseTimer(teaTask.id)
        runCurrent()
    }

    // `persisted-list-preferences`: durable arrangement, no-flicker restoration --------------------

    /**
     * AC-5, the most important test in this spec: seeds the fake repository with a **non-default**
     * arrangement *before* constructing the ViewModel (as if DataStore already held it from a
     * previous session), then proves `uiState`'s first non-`Loading` emission already reflects it -
     * never a default-arranged `Content` that would jump a moment later. D3 makes the "not loaded
     * yet" state `null` (`Loading`), so there is no default-valued frame this test could even catch
     * mid-flight; what it pins instead is that *every* `Content` emission this ViewModel ever
     * produces, from the very first one, is already `Grouped` by the seeded axis.
     */
    @Test
    fun `uiState's first non-Loading emission already carries a seeded non-default arrangement`() = runTest(testDispatcher) {
        val seededArrangement = TaskListCriteria(
            sortField = TaskSortField.Title,
            direction = SortDirection.Descending,
            groupAxis = TaskGroupAxis.Urgency,
        )
        val userPreferencesRepository = FakeUserPreferencesRepository(
            UserPreferences(taskListArrangement = seededArrangement),
        )
        val viewModel = TasksViewModel(
            motionSettings = FakeMotionSettings(),
            userPreferencesRepository = userPreferencesRepository,
            repository = FakeTaskRepository(listOf(teaTask, reportTask)),
        )

        val emissions = mutableListOf<TasksUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { emissions.add(it) }
        }
        advanceTimeBy(300)
        runCurrent()

        val nonLoadingEmissions = emissions.filterNot { it is TasksUiState.Loading }
        assertTrue("expected at least one non-Loading emission", nonLoadingEmissions.isNotEmpty())

        // The very first Content this ViewModel ever produces must already be Grouped by Urgency
        // (the seeded axis) - if restoration ever raced the first render, this would instead be a
        // Flat (default groupAxis = None) Content that later flips to Grouped.
        nonLoadingEmissions.forEach { state ->
            val content = state as TasksUiState.Content
            assertTrue(
                "every Content emission must already reflect the seeded arrangement, never the " +
                    "default groupAxis = None",
                content.shaped is ShapedTaskList.Grouped,
            )
        }

        collectCriteria(viewModel)
        advanceUntilIdle()
        assertEquals(seededArrangement, viewModel.criteria.value)
    }

    /**
     * AC-2/D1: `UserPreferences` carries no field at all for the text query or the priority
     * filter, so construction can only ever start [TasksViewModel.query]/`criteria.priorityFilter`
     * at their in-memory defaults - proven here against a repository seeded with a deliberately
     * *non*-default arrangement, so a bug that accidentally widened restoration to the whole
     * `TaskListCriteria` (rather than just sortField/direction/groupAxis) would show up as a
     * non-empty `priorityFilter` here.
     */
    @Test
    fun `query and priority filter never restore from storage, regardless of the seeded arrangement`() = runTest(testDispatcher) {
        val userPreferencesRepository = FakeUserPreferencesRepository(
            UserPreferences(
                taskListArrangement = TaskListCriteria(
                    sortField = TaskSortField.Priority,
                    direction = SortDirection.Descending,
                    groupAxis = TaskGroupAxis.Priority,
                ),
            ),
        )
        val viewModel = TasksViewModel(
            motionSettings = FakeMotionSettings(),
            userPreferencesRepository = userPreferencesRepository,
            repository = FakeTaskRepository(listOf(teaTask)),
        )
        collectCriteria(viewModel)
        advanceUntilIdle()

        assertEquals("", viewModel.query.value)
        assertEquals(emptySet<Priority>(), viewModel.criteria.value!!.priorityFilter)
    }

    /**
     * AC-6/D3: [TasksViewModel]'s `init {}` only launches the auto-pause and sync-status side-effect
     * collectors (see its KDoc) - it never seeds `arrangement`/`criteria` itself. This mirrors
     * `uiState stays at its Loading seed with no collector attached` above: with no collector
     * anywhere near `criteria`, `SharingStarted.WhileSubscribed` never starts collecting the
     * repository's flow, so advancing the scheduler changes nothing and `criteria.value` stays at
     * its `null` seed - even though the fake repository already holds a real, non-default
     * arrangement the moment it would be read.
     */
    @Test
    fun `criteria stays null with no collector attached - no imperative init seeds it`() {
        val userPreferencesRepository = FakeUserPreferencesRepository(
            UserPreferences(taskListArrangement = TaskListCriteria(sortField = TaskSortField.Title)),
        )
        val viewModel = TasksViewModel(
            motionSettings = FakeMotionSettings(),
            userPreferencesRepository = userPreferencesRepository,
            repository = FakeTaskRepository(listOf(teaTask)),
        )

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, viewModel.criteria.value)
    }
}
