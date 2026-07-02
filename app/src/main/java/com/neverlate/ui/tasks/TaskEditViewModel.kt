package com.neverlate.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskFormResult
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.data.tasks.TaskValidationError
import com.neverlate.data.tasks.formatDeadline
import com.neverlate.data.tasks.validateTaskForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The create/edit form's current field values and validation state. */
data class TaskEditUiState(
    val title: String = "",
    val estimatedDurationMinutes: String = "",
    val deadlineText: String = "",
    val validationError: TaskValidationError? = null,
    // A one-shot signal (not meant to survive recomposition) that the Route uses to navigate
    // back once save/delete has actually round-tripped through the repository.
    val isSaved: Boolean = false,
)

/**
 * Backs the task creation/edit form.
 *
 * [taskId] follows the same "navigation argument in, repository reload out" pattern as
 * [com.neverlate.ui.articles.ArticleDetailViewModel]'s `articleId`, with one difference: here
 * null is a *meaningful* value, not a missing one — null means "create a new task", while any
 * other id means "load and edit the task with that id".
 */
class TaskEditViewModel(
    private val repository: TaskRepository,
    private val taskId: Long?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskEditUiState())
    val uiState: StateFlow<TaskEditUiState> = _uiState.asStateFlow()

    // Keeps the loaded task's timer fields (timerEndsAt/remainingMillis) around so that saving an
    // edit never resets a countdown that happens to be running — only title/duration/deadline are
    // editable through this form.
    private var editingTask: Task? = null

    init {
        val id = taskId
        if (id != null) {
            viewModelScope.launch {
                val task = repository.observeTask(id).first() ?: return@launch
                editingTask = task
                _uiState.value = TaskEditUiState(
                    title = task.title,
                    estimatedDurationMinutes = task.estimatedDurationMillis
                        ?.let { (it / 60_000L).toString() }
                        .orEmpty(),
                    deadlineText = task.deadline?.let(::formatDeadline).orEmpty(),
                )
            }
        }
    }

    fun onTitleChange(title: String) {
        _uiState.value = _uiState.value.copy(title = title, validationError = null)
    }

    fun onDurationMinutesChange(minutes: String) {
        _uiState.value = _uiState.value.copy(estimatedDurationMinutes = minutes, validationError = null)
    }

    fun onDeadlineTextChange(text: String) {
        _uiState.value = _uiState.value.copy(deadlineText = text, validationError = null)
    }

    /** Validates the form (see [validateTaskForm]) and, if valid, persists it through [repository]. */
    fun save() {
        val state = _uiState.value
        when (val result = validateTaskForm(state.title, state.estimatedDurationMinutes, state.deadlineText)) {
            is TaskFormResult.Invalid -> _uiState.value = state.copy(validationError = result.error)
            is TaskFormResult.Valid -> {
                val task = (editingTask ?: Task(title = result.title)).copy(
                    title = result.title,
                    estimatedDurationMillis = result.durationMillis,
                    deadline = result.deadlineMillis,
                )
                viewModelScope.launch {
                    repository.saveTask(task)
                    _uiState.value = _uiState.value.copy(isSaved = true)
                }
            }
        }
    }

    /** Deletes the task being edited. A no-op while creating: there is nothing to delete yet. */
    fun deleteTask() {
        val id = taskId ?: return
        viewModelScope.launch {
            repository.deleteTask(id)
            _uiState.value = _uiState.value.copy(isSaved = true)
        }
    }
}
