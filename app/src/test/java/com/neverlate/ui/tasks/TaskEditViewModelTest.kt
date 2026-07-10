package com.neverlate.ui.tasks

import com.neverlate.data.tasks.Priority
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.data.tasks.TaskValidationError
import com.neverlate.data.tasks.parseDeadline
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory fake for [TaskRepository], separate from [TasksViewModelTest]'s copy — same
 * one-fake-per-test-file convention as [com.neverlate.ui.articles.ArticleDetailViewModelTest].
 * On top of serving [observeTask], it records every [saveTask]/[deleteTask] call so tests can
 * assert exactly what [TaskEditViewModel] asked it to persist.
 */
private class FakeTaskRepositoryForEdit(initialTasks: List<Task> = emptyList()) : TaskRepository {

    private val tasksFlow = MutableStateFlow(initialTasks)

    /** Every task this fake has been asked to save, in call order. */
    val savedTasks = mutableListOf<Task>()

    /** Every task id this fake has been asked to delete, in call order. */
    val deletedIds = mutableListOf<Long>()

    override fun observeTasks(): Flow<List<Task>> = tasksFlow

    override fun observeTask(id: Long): Flow<Task?> = tasksFlow.map { tasks -> tasks.firstOrNull { it.id == id } }

    override suspend fun saveTask(task: Task): Long {
        savedTasks += task
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
        deletedIds += id
        tasksFlow.update { tasks -> tasks.filterNot { it.id == id } }
    }

    // Not exercised by TaskEditViewModel: the countdown is only started/paused from the list
    // screen (see TasksViewModelTest), never from the create/edit form.
    override suspend fun startTimer(id: Long) = error("not used by TaskEditViewModel")

    override suspend fun pauseTimer(id: Long) = error("not used by TaskEditViewModel")
}

@OptIn(ExperimentalCoroutinesApi::class)
class TaskEditViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // Edit mode's init{} loads the existing task on viewModelScope (Dispatchers.Main);
        // StandardTestDispatcher + setMain lets the test control when that coroutine runs.
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `create mode starts with a blank form`() {
        val viewModel = TaskEditViewModel(FakeTaskRepositoryForEdit(), taskId = null)

        val state = viewModel.uiState.value
        assertEquals("", state.title)
        assertEquals("", state.estimatedDurationMinutes)
        assertEquals("", state.deadlineText)
        assertNull(state.validationError)
        assertFalse(state.isSaved)
    }

    @Test
    fun `edit mode preloads the existing task's fields`() {
        val deadlineText = "24/12/2026 20:30"
        val deadlineMillis = parseDeadline(deadlineText)!!
        val existing = Task(
            id = 7,
            title = "Repasar apuntes",
            estimatedDurationMillis = 90 * 60_000L,
            deadline = deadlineMillis,
        )
        val repository = FakeTaskRepositoryForEdit(listOf(existing))
        val viewModel = TaskEditViewModel(repository, taskId = existing.id)

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Repasar apuntes", state.title)
        assertEquals("90", state.estimatedDurationMinutes)
        assertEquals(deadlineText, state.deadlineText)
    }

    @Test
    fun `save with valid input persists the task and marks the form saved`() {
        val repository = FakeTaskRepositoryForEdit()
        val viewModel = TaskEditViewModel(repository, taskId = null)

        viewModel.onTitleChange("Llamar al dentista")
        viewModel.onDurationMinutesChange("15")
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repository.savedTasks.size)
        val saved = repository.savedTasks.single()
        assertEquals("Llamar al dentista", saved.title)
        assertEquals(15 * 60_000L, saved.estimatedDurationMillis)
        assertNull(saved.deadline)
        assertTrue(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `create mode defaults priority to NONE and persists a chosen priority`() {
        val repository = FakeTaskRepositoryForEdit()
        val viewModel = TaskEditViewModel(repository, taskId = null)

        assertEquals(Priority.NONE, viewModel.uiState.value.priority)

        viewModel.onTitleChange("Preparar informe")
        viewModel.onDurationMinutesChange("30")
        viewModel.onPriorityChange(Priority.HIGH)
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(Priority.HIGH, repository.savedTasks.single().priority)
    }

    @Test
    fun `edit mode preloads the existing task's priority`() {
        val existing = Task(
            id = 11,
            title = "Renovar el DNI",
            estimatedDurationMillis = 20 * 60_000L,
            priority = Priority.MEDIUM,
        )
        val repository = FakeTaskRepositoryForEdit(listOf(existing))
        val viewModel = TaskEditViewModel(repository, taskId = existing.id)

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(Priority.MEDIUM, viewModel.uiState.value.priority)
    }

    @Test
    fun `save with a blank title is blocked and shows a validation error`() {
        val repository = FakeTaskRepositoryForEdit()
        val viewModel = TaskEditViewModel(repository, taskId = null)

        viewModel.onDurationMinutesChange("15")
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(repository.savedTasks.isEmpty())
        assertEquals(TaskValidationError.BLANK_TITLE, viewModel.uiState.value.validationError)
        assertFalse(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `save without duration or deadline is blocked`() {
        val repository = FakeTaskRepositoryForEdit()
        val viewModel = TaskEditViewModel(repository, taskId = null)

        viewModel.onTitleChange("Tarea sin tiempo")
        viewModel.save()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(repository.savedTasks.isEmpty())
        assertEquals(TaskValidationError.MISSING_DURATION_OR_DEADLINE, viewModel.uiState.value.validationError)
    }

    @Test
    fun `editing an invalid field first clears the previous validation error`() {
        val repository = FakeTaskRepositoryForEdit()
        val viewModel = TaskEditViewModel(repository, taskId = null)
        viewModel.save() // blank title -> sets validationError
        assertEquals(TaskValidationError.BLANK_TITLE, viewModel.uiState.value.validationError)

        viewModel.onTitleChange("Ahora sí")

        assertNull(viewModel.uiState.value.validationError)
    }

    @Test
    fun `deleteTask removes the task being edited`() {
        val existing = Task(id = 9, title = "Tarea a borrar", estimatedDurationMillis = 10 * 60_000L)
        val repository = FakeTaskRepositoryForEdit(listOf(existing))
        val viewModel = TaskEditViewModel(repository, taskId = existing.id)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.deleteTask()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(existing.id), repository.deletedIds)
        assertTrue(viewModel.uiState.value.isSaved)
    }

    @Test
    fun `deleteTask in create mode is a no-op`() {
        val repository = FakeTaskRepositoryForEdit()
        val viewModel = TaskEditViewModel(repository, taskId = null)

        viewModel.deleteTask()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(repository.deletedIds.isEmpty())
    }
}
