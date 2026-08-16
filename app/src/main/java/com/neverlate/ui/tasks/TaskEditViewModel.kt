package com.neverlate.ui.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.tasks.Priority
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskFormResult
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.data.tasks.TaskValidationError
import com.neverlate.data.tasks.durationParts
import com.neverlate.data.tasks.formatDeadlineForInput
import com.neverlate.data.tasks.validateTaskForm
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** [SavedStateHandle] key for the `taskId` navigation argument — see `AppNavHost`'s `Routes.TASK_EDIT`. */
private const val ARG_TASK_ID = "taskId"

/** The create/edit form's current field values and validation state. */
data class TaskEditUiState(
    val title: String = "",
    val durationHours: String = "",
    val durationMinutes: String = "",
    val deadlineText: String = "",
    val priority: Priority = Priority.NONE,
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
 *
 * Feature 13d: [taskId] now arrives via [SavedStateHandle] instead of a plain constructor
 * parameter built by the retired `AppViewModelFactory`. Unlike [com.neverlate.ui.articles.ArticleDetailViewModel]'s
 * `articleId`, a missing value here must **not** throw: the create-only `Routes.TASK_EDIT`
 * composable declares no `{taskId}` argument at all (see `AppNavHost`), so its `SavedStateHandle`
 * genuinely has no `taskId` entry — `savedStateHandle["taskId"]` simply returns null in that case,
 * which is exactly the "create a new task" signal this class already relied on.
 */
@HiltViewModel
class TaskEditViewModel @Inject constructor(
    private val repository: TaskRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val taskId: Long? = savedStateHandle.get<Long>(ARG_TASK_ID)

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
                // Split the stored millis into (hours, minutes) via the shared TaskTiming helper
                // (US-2) rather than hand-rolling the division here. A part of 0 renders as an
                // empty field, not "0" — an untouched duration-only field reads cleaner that way,
                // and 0 h 90 min normalizing on save still round-trips to the same millis.
                val (hours, minutes) = task.estimatedDurationMillis
                    ?.let(::durationParts)
                    ?: (0L to 0L)
                _uiState.value = TaskEditUiState(
                    title = task.title,
                    durationHours = hours.takeIf { it != 0L }?.toString().orEmpty(),
                    durationMinutes = minutes.takeIf { it != 0L }?.toString().orEmpty(),
                    deadlineText = task.deadline?.let(::formatDeadlineForInput).orEmpty(),
                    priority = task.priority,
                )
            }
        }
    }

    fun onTitleChange(title: String) {
        _uiState.value = _uiState.value.copy(title = title, validationError = null)
    }

    fun onDurationHoursChange(hours: String) {
        _uiState.value = _uiState.value.copy(durationHours = hours, validationError = null)
    }

    fun onDurationMinutesChange(minutes: String) {
        _uiState.value = _uiState.value.copy(durationMinutes = minutes, validationError = null)
    }

    fun onDeadlineTextChange(text: String) {
        _uiState.value = _uiState.value.copy(deadlineText = text, validationError = null)
    }

    fun onPriorityChange(priority: Priority) {
        _uiState.value = _uiState.value.copy(priority = priority)
    }

    /** Validates the form (see [validateTaskForm]) and, if valid, persists it through [repository]. */
    fun save() {
        val state = _uiState.value
        when (
            val result = validateTaskForm(state.title, state.durationHours, state.durationMinutes, state.deadlineText)
        ) {
            is TaskFormResult.Invalid -> _uiState.value = state.copy(validationError = result.error)
            is TaskFormResult.Valid -> {
                val task = (editingTask ?: Task(title = result.title)).copy(
                    title = result.title,
                    estimatedDurationMillis = result.durationMillis,
                    deadline = result.deadlineMillis,
                    priority = state.priority,
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
