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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
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
 *
 * Feature 04b rewrites how [uiState] itself is produced: instead of one imperative `collect` that
 * assigns a `MutableStateFlow` by hand, [uiState] is now a **declarative** chain of `Flow`
 * operators — `combine` + `debounce` + `stateIn` — a direct look at the "corrutinas y `Flow` a
 * fondo" lesson (`tutorial/04b-buscador-tareas.md`). The list-shaping logic itself
 * ([com.neverlate.domain.tasks.shapedBy], feature 03b) is unchanged; only the plumbing that feeds
 * it changed.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class TasksViewModel(private val repository: TaskRepository) : ViewModel() {

    /**
     * Feature 04b: the search field's raw text, as its **own** [MutableStateFlow] — deliberately
     * separate from [_criteria] below. [TasksScreen]'s field reads [query] directly, so every
     * keystroke is reflected on screen the instant it happens (US-1); only the *derived filtering*
     * downstream of [debouncedQuery] waits for a pause in typing. Keeping the query and the
     * sort/group criteria as two different `StateFlow`s is what lets one be debounced while the
     * other stays immediate.
     */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * Feature 03b: sort field, direction, and grouping — everything *except* the text query
     * (feature 04b moved that out, see [_query] above). These three stay immediate: touching a
     * sort chip re-shapes the visible list on the very next [combine] emission below, with no
     * debounce — only free-text search benefits from waiting out a typing burst.
     */
    private val _criteria = MutableStateFlow(TaskListCriteria())
    val criteria: StateFlow<TaskListCriteria> = _criteria.asStateFlow()

    /**
     * Feature 11's minimal sync indicator (OQ-1): forwarded straight from
     * [TaskRepository.observeSyncStatus] — this ViewModel touches nothing sync-shaped beyond that
     * one additive method (see [TaskRepository]'s KDoc, US-7). [refresh] is what
     * `PullToRefreshBox`'s gesture (see [TasksScreen]) calls.
     */
    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    /**
     * The Room-backed task stream, unchanged by feature 04b: [TaskRepository.observeTasks],
     * switched (via [flatMapLatest]) to the once-a-second [countdownTicker] while any task is
     * running, then mapped to [TaskUiModel]s. Feature 04b adds operators **on top of** this Flow
     * (see [uiState] below) rather than opening a second stream toward Room — there is still only
     * ever one read of the task list.
     */
    private val uiTasksFlow: Flow<List<TaskUiModel>> = repository.observeTasks()
        .flatMapLatest { tasks ->
            if (tasks.any { it.isRunning }) countdownTicker().map { tasks } else flowOf(tasks)
        }
        .map { tasks -> tasks.toUiModels() }

    /**
     * `debounce` (a new `Flow` time operator, feature 04b's central concept): re-emits a value
     * from [_query] only once **300ms** pass with no further change — every keystroke typed faster
     * than that is silently superseded by the next one, so typing "presentacion" letter by letter
     * produces a single settled emission here, not eleven. `distinctUntilChanged` then drops a
     * re-emission that happens to equal what is already downstream (e.g. typing a character and
     * deleting it again within the debounce window), so returning to an unchanged query never
     * re-triggers [shapedBy] a second time for nothing.
     *
     * Every new value arriving at `debounce` restarts its internal delay and cancels the
     * previously pending one — this is `Flow`'s built-in *structured* cancellation at work (US-2):
     * an in-flight "wait 300ms" for an old keystroke is abandoned the moment a newer keystroke
     * arrives, so only ever the *last* typed value can win.
     */
    private val debouncedQuery: Flow<String> = _query
        .debounce(300)
        .distinctUntilChanged()

    /**
     * `combine` (feature 04b): builds a `Flow` that re-emits every time **any** of its three
     * sources produces a new value — a fresh countdown tick from [uiTasksFlow], a settled query
     * from [debouncedQuery], or a sort/group change from [_criteria] — always pairing each
     * source's *latest* value, never a stale one. That is exactly US-3's requirement: the visible
     * list is a pure, declarative function of "the newest of each of these three things".
     *
     * `combine`'s result is a **cold** `Flow`: like any other `Flow`, it does no work at all until
     * something collects it, and would restart from scratch for a second independent collector.
     * [stateIn] converts it into a **hot** [StateFlow] instead — one single upstream computation,
     * shared by every collector, that always holds a *current* value even between emissions
     * (exactly what [kotlinx.coroutines.flow.SharedFlow] does **not** guarantee, since a
     * `SharedFlow` has no required "current value" and no built-in initial value — which is why a
     * screen's derived UI state belongs in a `StateFlow`, not a `SharedFlow`).
     * [SharingStarted.WhileSubscribed] is the sharing policy that decides *when* that shared
     * upstream work actually runs: only while at least one collector (here, [TasksScreen] via
     * `collectAsStateWithLifecycle`) is present, continuing for `5_000`ms after the last collector
     * leaves — long enough to survive the brief collector gap of a configuration change without
     * restarting the whole pipeline, short enough to stop the ticker/combine chain soon after the
     * screen is genuinely gone. `Eagerly` would keep this running for [viewModelScope]'s entire
     * lifetime even with nobody watching; `Lazily` would never stop once started. The third
     * argument, [TasksUiState.Loading], is the value any collector sees before the first `combine`
     * emission arrives — [stateIn]'s "always has a current value" guarantee needs *some* seed.
     */
    val uiState: StateFlow<TasksUiState> =
        combine(uiTasksFlow, debouncedQuery, _criteria) { uiTasks, settledQuery, criteria ->
            shapeToUiState(uiTasks, settledQuery, criteria)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState.Loading)

    init {
        // The auto-pause side effect used to live inside the same imperative collect that also
        // assigned _uiState (pre-04b). It now has its own collector, entirely separate from the
        // pure uiState derivation above: uiState answers "what should the screen show", while this
        // answers "what should happen as a result" — mixing the two would make uiState's
        // derivation impure and harder to test/reason about on its own. onEach + launchIn is the
        // idiomatic way to attach a side effect to an existing Flow without collecting it by hand
        // a second time (which would mean two independent flatMapLatest/ticker chains running).
        uiTasksFlow.onEach { uiTasks -> autoPauseTimedOut(uiTasks) }.launchIn(viewModelScope)

        viewModelScope.launch {
            repository.observeSyncStatus().collect { status -> _syncStatus.value = status }
        }
    }

    /** Triggers an on-demand sync (US-4) — bound to [TasksScreen]'s pull-to-refresh gesture. */
    fun refresh() {
        viewModelScope.launch { repository.refreshFromServer() }
    }

    /**
     * US-1: updates the field's text immediately. The *effective* filter downstream only catches
     * up ~300ms after typing settles (see [debouncedQuery]) — this setter itself never waits.
     */
    fun onQueryChange(query: String) {
        _query.value = query
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

    /**
     * The pure derivation [combine] above calls on every emission: no tasks at all is [Empty];
     * otherwise [shapedBy] (feature 03b, unchanged) filters/sorts/groups, and an empty result
     * after filtering is [NoResults] rather than [Empty] (US-4 — two different reasons for an
     * empty screen, two different messages).
     */
    private fun shapeToUiState(
        uiTasks: List<TaskUiModel>,
        query: String,
        criteria: TaskListCriteria,
    ): TasksUiState {
        if (uiTasks.isEmpty()) return TasksUiState.Empty
        val shaped = uiTasks.shapedBy(query, criteria)
        return if (shaped.isEmpty()) TasksUiState.NoResults else TasksUiState.Content(shaped)
    }

    /**
     * A running countdown that just reached zero stops itself here: pausing freezes
     * remainingMillis at 0 and clears timerEndsAt, which both keeps the UI from ever showing a
     * negative number (US-5) and — via observeTasks()'s Flow — lets flatMapLatest above drop the
     * now-pointless tick for that task.
     */
    private fun autoPauseTimedOut(uiTasks: List<TaskUiModel>) {
        uiTasks.filter { it.task.isRunning && it.remainingMillis == 0L }
            .forEach { pauseTimer(it.task.id) }
    }

    fun startTimer(taskId: Long) = viewModelScope.launch { repository.startTimer(taskId) }

    fun pauseTimer(taskId: Long) = viewModelScope.launch { repository.pauseTimer(taskId) }

    fun deleteTask(taskId: Long) = viewModelScope.launch { repository.deleteTask(taskId) }

    /**
     * US-1 (feature 04c): flips [task]'s completion — `completedAt = now` if it was pending,
     * `null` (undo) if it was already done. Goes through the normal [TaskRepository.saveTask]
     * path, exactly like editing any other field, so it writes the task row **and** its outbox
     * change row in one transaction (feature 11's decorator chain) with no special-casing here.
     * [now] follows [toUiModels]'s existing convention (a defaulted parameter rather than an
     * inline [System.currentTimeMillis] call) so a test can pin the exact instant.
     */
    fun toggleComplete(task: Task, now: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            repository.saveTask(task.copy(completedAt = if (task.completedAt == null) now else null))
        }
    }
}
