package com.neverlate.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.sync.SyncStatus
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.data.tasks.computeRemainingMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A single task paired with its remaining countdown time, recomputed on every tick. */
data class TaskUiModel(val task: Task, val remainingMillis: Long, val isTimedOut: Boolean)

/** Everything the Tasks list screen needs to render itself. */
sealed interface TasksUiState {
    data object Loading : TasksUiState
    data class Content(val tasks: List<TaskUiModel>) : TasksUiState
    data object Empty : TasksUiState
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

    init {
        viewModelScope.launch {
            repository.observeTasks()
                .flatMapLatest { tasks ->
                    if (tasks.any { it.isRunning }) countdownTicker().map { tasks } else flowOf(tasks)
                }
                .collect { tasks -> onTasksTick(tasks) }
        }

        viewModelScope.launch {
            repository.observeSyncStatus().collect { status -> _syncStatus.value = status }
        }
    }

    /** Triggers an on-demand sync (US-4) — bound to [TasksScreen]'s pull-to-refresh gesture. */
    fun refresh() {
        viewModelScope.launch { repository.refreshFromServer() }
    }

    private fun onTasksTick(tasks: List<Task>) {
        val now = System.currentTimeMillis()
        val uiTasks = tasks.map { task ->
            val remaining = computeRemainingMillis(task, now)
            TaskUiModel(task = task, remainingMillis = remaining, isTimedOut = remaining == 0L)
        }
        _uiState.value = if (uiTasks.isEmpty()) TasksUiState.Empty else TasksUiState.Content(uiTasks)

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
