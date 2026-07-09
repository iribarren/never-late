package com.neverlate.ui.stats

import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.domain.tasks.WeeklyTaskStats
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory fake for [TaskRepository] — same one-fake-per-test-file convention as
 * [com.neverlate.ui.tasks.TasksViewModelTest]. [StatsViewModel] only ever calls [observeTasks], so
 * every other method here is a minimal stub, not a full simulation.
 */
private class FakeTaskRepository(initialTasks: List<Task> = emptyList()) : TaskRepository {

    private val tasksFlow = MutableStateFlow(initialTasks)

    override fun observeTasks(): Flow<List<Task>> = tasksFlow

    override fun observeTask(id: Long): Flow<Task?> = tasksFlow.map { tasks -> tasks.firstOrNull { it.id == id } }

    override suspend fun saveTask(task: Task): Long = task.id

    override suspend fun deleteTask(id: Long) {}

    override suspend fun startTimer(id: Long) {}

    override suspend fun pauseTimer(id: Long) {}

    /** Pushes a new task list downstream, as [TaskRepository.observeTasks] would on a real change. */
    fun setTasks(tasks: List<Task>) {
        tasksFlow.value = tasks
    }
}

/**
 * Coroutine/[kotlinx.coroutines.flow.StateFlow] tests for [StatsViewModel] (feature 04c's
 * `runTest`/[StandardTestDispatcher] demonstration): unlike [weeklyStatsFor][com.neverlate.domain.tasks.weeklyStatsFor]
 * itself (a pure function, no fake clock needed — see `TaskStatsTest`), this ViewModel *does* read
 * a real [java.time.Clock], so here a fixed one stands in for it, and [runTest] gives full control
 * over the virtual time [uiState]'s `stateIn` sharing needs to actually emit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    // Same non-UTC-zone, fixed-instant convention as TaskStatsTest, so this suite's "now" never
    // depends on the machine actually running it.
    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val now = ZonedDateTime.of(2024, 1, 10, 15, 30, 0, 0, zone).toInstant()
    private val clock: Clock = Clock.fixed(now, zone)
    private val weekStart = ZonedDateTime.of(2024, 1, 8, 0, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun completedOnTimeTask() = Task(
        id = 1,
        title = "Completed on time",
        completedAt = weekStart + 1_000L,
        deadline = weekStart + 2_000L,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** [StatsViewModel.uiState] is a [kotlinx.coroutines.flow.StateFlow] behind
     *  `stateIn(SharingStarted.WhileSubscribed(...))` — like [com.neverlate.ui.tasks.TasksViewModel]'s
     *  own `uiState`, it does no work at all until something collects it. */
    private fun TestScope.collectUiState(viewModel: StatsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
    }

    @Test
    fun `uiState stays at its Loading seed with no collector attached`() {
        val viewModel = StatsViewModel(FakeTaskRepository(listOf(completedOnTimeTask())), clock)

        // No collector attached: WhileSubscribed never starts the upstream map, so advancing the
        // scheduler changes nothing here — contrast with every test below.
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is StatsUiState.Loading)
    }

    @Test
    fun `empty repository produces Empty state`() = runTest(testDispatcher) {
        val viewModel = StatsViewModel(FakeTaskRepository(emptyList()), clock)
        collectUiState(viewModel)

        runCurrent()

        assertTrue(viewModel.uiState.value is StatsUiState.Empty)
    }

    @Test
    fun `repository with tasks produces Content with the weeklyStatsFor result for the fixed clock`() =
        runTest(testDispatcher) {
            val onTime = completedOnTimeTask()
            val dueSoon = Task(id = 2, title = "Due soon", deadline = now.toEpochMilli() + 1_000L)
            val viewModel = StatsViewModel(FakeTaskRepository(listOf(onTime, dueSoon)), clock)
            collectUiState(viewModel)

            runCurrent()

            val state = viewModel.uiState.value
            assertTrue(state is StatsUiState.Content)
            assertEquals(
                WeeklyTaskStats(completedThisWeek = 1, onTimePercent = 100, dueSoon = 1),
                (state as StatsUiState.Content).stats,
            )
        }

    @Test
    fun `uiState re-derives when the observed task list changes, using the same fixed clock`() = runTest(testDispatcher) {
        val repository = FakeTaskRepository(emptyList())
        val viewModel = StatsViewModel(repository, clock)
        collectUiState(viewModel)
        runCurrent()
        assertTrue(viewModel.uiState.value is StatsUiState.Empty)

        // The task list changes underneath the ViewModel (as a real repository's Flow would after
        // a save) — the clock never moves, only the observed data does.
        repository.setTasks(listOf(completedOnTimeTask()))
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state is StatsUiState.Content)
        assertEquals(1, (state as StatsUiState.Content).stats.completedThisWeek)
    }
}
