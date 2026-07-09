package com.neverlate.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.sync.SyncStatus
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.data.tasks.computeRemainingMillis
import com.neverlate.domain.tasks.ShapedTaskList
import com.neverlate.domain.tasks.SortDirection
import com.neverlate.domain.tasks.TaskListCriteria
import com.neverlate.domain.tasks.TaskSortField
import com.neverlate.domain.tasks.isEmpty
import com.neverlate.domain.tasks.shapedBy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A single task paired with its remaining countdown time, recomputed on every tick. */
data class TaskUiModel(val task: Task, val remainingMillis: Long, val isTimedOut: Boolean)

/**
 * Everything the Tasks list screen needs to render itself. [NoResults] (feature 03b, US-4) is
 * deliberately a **separate** state from [Empty], not a flag on [Content]: "you have no tasks"
 * and "your filter matched none of your tasks" call for different messages and a different
 * action (create a task vs. clear the filter), so keeping them as distinct `sealed interface`
 * members forces every renderer's `when` to handle both instead of letting one silently stand in
 * for the other.
 */
sealed interface TasksUiState {
    data object Loading : TasksUiState
    data class Content(val shaped: ShapedTaskList) : TasksUiState
    data object Empty : TasksUiState
    data object NoResults : TasksUiState
}

/**
 * Loads the task list from [repository] and keeps each task's remaining countdown time fresh.
 *
 * Unlike [com.neverlate.ui.articles.ArticlesViewModel] (a one-shot load), this ViewModel
 * continuously observes [TaskRepository.observeTasks] and, while at least one task is running,
 * also re-derives every task's remaining time once a second via [countdownTicker] — this is the
 * coroutine/Flow-based timer the feature spec calls for, kept entirely out of the UI layer (the
 * screen only ever reads [uiState]).
 *
 * [flatMapLatest] switches the upstream Flow to (or away from) the ticker every time the task
 * list itself changes: as soon as no task is running, it swaps to a plain [flowOf] that emits
 * once and does nothing else, so the once-a-second tick — and the battery it would otherwise
 * spend — stops automatically. Starting, pausing, and timing out a task all go through
 * [repository], which updates the persisted rows [TaskRepository.observeTasks] observes, so this
 * switch happens without this ViewModel needing to track "is anything running" itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModel(private val repository: TaskRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<TasksUiState>(TasksUiState.Loading)
    val uiState: StateFlow<TasksUiState> = _uiState.asStateFlow()

    /**
     * Feature 11's minimal sync indicator (OQ-1): forwarded straight from
     * [TaskRepository.observeSyncStatus] — this ViewModel touches nothing sync-shaped beyond that
     * one additive method (see [TaskRepository]'s KDoc, US-7). [refresh] is what
     * `PullToRefreshBox`'s gesture (see [TasksScreen]) calls.
     */
    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    /**
     * Feature 03b: the Tasks screen's search/sort/group controls, all in one value the screen
     * reads and updates via [onQueryChange]/[onSortFieldChange]/[onToggleSortDirection]/
     * [onToggleGrouping]. This lives entirely in memory (Out of Scope: no DataStore persistence),
     * so it resets to [TaskListCriteria]'s defaults whenever this ViewModel is recreated.
     */
    private val _criteria = MutableStateFlow(TaskListCriteria())
    val criteria: StateFlow<TaskListCriteria> = _criteria.asStateFlow()

    init {
        viewModelScope.launch {
            val uiTasksFlow = repository.observeTasks()
                .flatMapLatest { tasks ->
                    if (tasks.any { it.isRunning }) countdownTicker().map { tasks } else flowOf(tasks)
                }
                .map { tasks -> tasks.toUiModels() }

            // combine re-emits whenever *either* source changes: a fresh tick from
            // uiTasksFlow, or the user touching a search/sort/group control — so criteria
            // changes apply immediately, without waiting for the next one-second tick, and a
            // running countdown keeps the currently-chosen criteria applied on every tick (US-1's
            // "mientras hay un temporizador corriendo, el filtro se mantiene aplicado").
            uiTasksFlow.combine(_criteria) { uiTasks, criteria -> uiTasks to criteria }
                .collect { (uiTasks, criteria) -> onTasksTick(uiTasks, criteria) }
        }

        viewModelScope.launch {
            repository.observeSyncStatus().collect { status -> _syncStatus.value = status }
        }
    }

    /** Triggers an on-demand sync (US-4) — bound to [TasksScreen]'s pull-to-refresh gesture. */
    fun refresh() {
        viewModelScope.launch { repository.refreshFromServer() }
    }

    /** US-1: replaces the current text filter. An empty string clears it back to "show all". */
    fun onQueryChange(query: String) {
        _criteria.value = _criteria.value.copy(query = query)
    }

    /** US-2: switches which field the list is sorted by, keeping the current direction. */
    fun onSortFieldChange(field: TaskSortField) {
        _criteria.value = _criteria.value.copy(sortField = field)
    }

    /** US-2: flips ascending ↔ descending for whichever field is currently selected. */
    fun onToggleSortDirection() {
        val current = _criteria.value
        val flipped = when (current.direction) {
            SortDirection.Ascending -> SortDirection.Descending
            SortDirection.Descending -> SortDirection.Ascending
        }
        _criteria.value = current.copy(direction = flipped)
    }

    /** US-3: turns the urgency-section grouping on/off. */
    fun onToggleGrouping() {
        _criteria.value = _criteria.value.copy(grouped = !_criteria.value.grouped)
    }

    /** Maps freshly-observed [Task] rows to their [TaskUiModel] countdown snapshot, all read
     *  against a single [now] so every task in the same tick agrees on "the current instant". */
    private fun List<Task>.toUiModels(now: Long = System.currentTimeMillis()): List<TaskUiModel> =
        map { task ->
            val remaining = computeRemainingMillis(task, now)
            TaskUiModel(task = task, remainingMillis = remaining, isTimedOut = remaining == 0L)
        }

    private fun onTasksTick(uiTasks: List<TaskUiModel>, criteria: TaskListCriteria) {
        _uiState.value = if (uiTasks.isEmpty()) {
            // No tasks at all — distinct from NoResults below, which needs at least one task to
            // exist before a filter can rule all of them out (US-4).
            TasksUiState.Empty
        } else {
            val shaped = uiTasks.shapedBy(criteria)
            if (shaped.isEmpty()) TasksUiState.NoResults else TasksUiState.Content(shaped)
        }

        // A running countdown that just reached zero stops itself here: pausing freezes
        // remainingMillis at 0 and clears timerEndsAt, which both keeps the UI from ever showing
        // a negative number (US-5) and — via observeTasks()'s Flow — lets flatMapLatest above
        // drop the now-pointless tick for that task.
        uiTasks.filter { it.task.isRunning && it.remainingMillis == 0L }
            .forEach { pauseTimer(it.task.id) }
    }

    fun startTimer(taskId: Long) = viewModelScope.launch { repository.startTimer(taskId) }

    fun pauseTimer(taskId: Long) = viewModelScope.launch { repository.pauseTimer(taskId) }

    fun deleteTask(taskId: Long) = viewModelScope.launch { repository.deleteTask(taskId) }
}
