package com.neverlate.ui.tasks

import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.data.tasks.computeRemainingMillis
import com.neverlate.domain.tasks.ShapedTaskList
import com.neverlate.domain.tasks.SortDirection
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

    override fun observeTasks(): Flow<List<Task>> = tasksFlow

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

    @Test
    fun `uiState stays at its Loading seed with no collector attached`() {
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(teaTask)))

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
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(teaTask, reportTask)))
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
        val viewModel = TasksViewModel(FakeTaskRepository(emptyList()))
        collectUiState(viewModel)

        advanceTimeBy(300)
        runCurrent()

        assertTrue(viewModel.uiState.value is TasksUiState.Empty)
    }

    @Test
    fun `startTimer marks the task running and remaining stays close to its full duration`() = runTest(testDispatcher) {
        val repository = FakeTaskRepository(listOf(teaTask))
        val viewModel = TasksViewModel(repository)
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
    }

    @Test
    fun `pauseTimer stops the countdown and freezes remaining time`() = runTest(testDispatcher) {
        val repository = FakeTaskRepository(listOf(teaTask))
        val viewModel = TasksViewModel(repository)
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
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(expiredTask)))
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
        val viewModel = TasksViewModel(repository)
        collectUiState(viewModel)
        advanceTimeBy(300)
        runCurrent()

        viewModel.deleteTask(teaTask.id)
        runCurrent()

        assertTrue(viewModel.uiState.value is TasksUiState.Empty)
    }

    // Feature 03b: sort / group criteria (immediate, no debounce) ----------------------------------

    @Test
    fun `criteria starts at its defaults - deadline ascending, ungrouped`() {
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(teaTask)))

        assertEquals(TaskListCriteria(), viewModel.criteria.value)
    }

    @Test
    fun `onSortFieldChange updates criteria's sortField and keeps the current direction`() {
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(teaTask)))

        viewModel.onSortFieldChange(TaskSortField.Title)

        assertEquals(TaskSortField.Title, viewModel.criteria.value.sortField)
        assertEquals(SortDirection.Ascending, viewModel.criteria.value.direction)
    }

    @Test
    fun `onToggleSortDirection flips ascending to descending and back`() {
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(teaTask)))

        viewModel.onToggleSortDirection()
        assertEquals(SortDirection.Descending, viewModel.criteria.value.direction)

        viewModel.onToggleSortDirection()
        assertEquals(SortDirection.Ascending, viewModel.criteria.value.direction)
    }

    @Test
    fun `onToggleGrouping flips the grouped flag and produces a Grouped shaped result`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(teaTask)))
        collectUiState(viewModel)
        advanceTimeBy(300)
        runCurrent()

        viewModel.onToggleGrouping()
        runCurrent()

        assertTrue(viewModel.criteria.value.grouped)
        val state = viewModel.uiState.value
        assertTrue(state is TasksUiState.Content)
        assertTrue((state as TasksUiState.Content).shaped is ShapedTaskList.Grouped)
    }

    @Test
    fun `onToggleGrouping twice returns to an ungrouped Flat result`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(teaTask)))
        collectUiState(viewModel)
        advanceTimeBy(300)
        runCurrent()

        viewModel.onToggleGrouping()
        viewModel.onToggleGrouping()
        runCurrent()

        assertFalse(viewModel.criteria.value.grouped)
        val state = viewModel.uiState.value
        assertTrue(state is TasksUiState.Content)
        assertTrue((state as TasksUiState.Content).shaped is ShapedTaskList.Flat)
    }

    // Feature 04b: reactive search - debounce, combine, distinctUntilChanged, cancellation --------

    @Test
    fun `onQueryChange updates query immediately, independent of the debounce`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(teaTask, reportTask)))
        collectUiState(viewModel)
        advanceUntilIdle()

        viewModel.onQueryChange("té")

        // US-1: the field's own StateFlow updates on the spot - no debounce at the setter, even
        // though the filtered uiState below has not caught up yet.
        assertEquals("té", viewModel.query.value)
    }

    @Test
    fun `typing letter by letter only filters once after the pause, not on every keystroke`() = runTest(testDispatcher) {
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(teaTask, reportTask)))
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
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(teaTask, reportTask)))
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
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(teaTask)))
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
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(teaTask, reportTask)))
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
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(teaTask, reportTask)))
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
        val viewModel = TasksViewModel(repository)
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
}
