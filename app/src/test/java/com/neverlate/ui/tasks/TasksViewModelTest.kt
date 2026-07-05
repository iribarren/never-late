package com.neverlate.ui.tasks

import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.data.tasks.computeRemainingMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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
        // throughout once a task is running, because the ticker's `delay(1000)` reschedules
        // itself forever while any task keeps running — advancing virtual time to "idle" would
        // never terminate.
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Loading before the repository flow is collected`() {
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(teaTask)))

        assertTrue(viewModel.uiState.value is TasksUiState.Loading)
    }

    @Test
    fun `repository with tasks produces Content state with computed remaining time`() {
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(teaTask, reportTask)))

        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state is TasksUiState.Content)
        val tasks = (state as TasksUiState.Content).tasks
        assertEquals(2, tasks.size)
        val tea = tasks.first { it.task.id == teaTask.id }
        assertEquals(teaTask.estimatedDurationMillis, tea.remainingMillis)
        assertFalse(tea.isTimedOut)
    }

    @Test
    fun `empty repository produces Empty state`() {
        val viewModel = TasksViewModel(FakeTaskRepository(emptyList()))

        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value is TasksUiState.Empty)
    }

    @Test
    fun `startTimer marks the task running and remaining stays close to its full duration`() {
        val repository = FakeTaskRepository(listOf(teaTask))
        val viewModel = TasksViewModel(repository)
        testDispatcher.scheduler.runCurrent()

        viewModel.startTimer(teaTask.id)
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value as TasksUiState.Content
        val uiTask = state.tasks.single()
        assertTrue(uiTask.task.isRunning)
        assertTrue(
            "remaining (${uiTask.remainingMillis}) should stay close to the full duration " +
                "(${teaTask.estimatedDurationMillis}) right after starting",
            abs(teaTask.estimatedDurationMillis!! - uiTask.remainingMillis) < 2_000L,
        )
    }

    @Test
    fun `pauseTimer stops the countdown and freezes remaining time`() {
        val repository = FakeTaskRepository(listOf(teaTask))
        val viewModel = TasksViewModel(repository)
        testDispatcher.scheduler.runCurrent()
        viewModel.startTimer(teaTask.id)
        testDispatcher.scheduler.runCurrent()

        viewModel.pauseTimer(teaTask.id)
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value as TasksUiState.Content
        val uiTask = state.tasks.single()
        assertFalse(uiTask.task.isRunning)
        assertEquals(uiTask.remainingMillis, uiTask.task.remainingMillis)
    }

    @Test(timeout = 5_000)
    fun `countdown reaching zero auto-pauses the task and marks it timed out`() {
        // Already expired when observed: timerEndsAt is in the past, so the very first tick
        // computes a remaining time of zero (US-5's "no negative values" rule).
        val expiredTask = Task(id = 3, title = "Tarea vencida", timerEndsAt = 1L)
        val viewModel = TasksViewModel(FakeTaskRepository(listOf(expiredTask)))

        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value as TasksUiState.Content
        val uiTask = state.tasks.single()
        assertTrue(uiTask.isTimedOut)
        assertEquals(0L, uiTask.remainingMillis)
        assertFalse("task should auto-pause once its countdown reaches zero", uiTask.task.isRunning)
    }

    @Test
    fun `deleteTask removes the task from the list`() {
        val repository = FakeTaskRepository(listOf(teaTask))
        val viewModel = TasksViewModel(repository)
        testDispatcher.scheduler.runCurrent()

        viewModel.deleteTask(teaTask.id)
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value is TasksUiState.Empty)
    }
}
