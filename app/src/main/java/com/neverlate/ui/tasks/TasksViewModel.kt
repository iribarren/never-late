package com.neverlate.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.settings.MotionSettings
import com.neverlate.data.sync.SyncStatus
import com.neverlate.data.tasks.Priority
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.data.tasks.computeRemainingMillis
import com.neverlate.domain.tasks.ShapedTaskList
import com.neverlate.domain.tasks.SortDirection
import com.neverlate.domain.tasks.TaskGroupAxis
import com.neverlate.domain.tasks.TaskListCriteria
import com.neverlate.domain.tasks.TaskSortField
import com.neverlate.domain.tasks.isEmpty
import com.neverlate.domain.tasks.shapedBy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
 * Maps freshly-observed [Task] rows to their [TaskUiModel] countdown snapshot, all read against a
 * single [now] so every task in the same tick agrees on "the current instant". Promoted out of
 * [TasksViewModel] (Modo Foco, `docs/specs/2026-08-18-focus-mode.md`, D10) to a top-level function
 * so [com.neverlate.ui.focus.FocusViewModel] can reuse the exact same conversion instead of a
 * second, drift-prone copy — same "one mapping, every consumer reuses it" rule the rest of this
 * codebase already applies (see `domain/tasks/ColorRole.kt`).
 */
fun List<Task>.toTaskUiModels(now: Long = System.currentTimeMillis()): List<TaskUiModel> =
    map { task ->
        val remaining = computeRemainingMillis(task, now)
        TaskUiModel(task = task, remainingMillis = remaining, isTimedOut = remaining == 0L)
    }

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
 * also re-derives every task's remaining time via [countdownTicker] — this is the coroutine/Flow-
 * based timer the feature spec calls for, kept entirely out of the UI layer (the screen only ever
 * reads [uiState]). The cadence itself is once a second under normal motion, coarser under reduced
 * motion (`reduce-motion` spec, D4) — see [tickIntervalFor] and [uiTasksFlow].
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
 *
 * Feature 13d: `@HiltViewModel` + `@Inject constructor`, obtained via `hiltViewModel()` instead of
 * the retired `AppViewModelFactory`.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class TasksViewModel @Inject constructor(
    private val repository: TaskRepository,
    private val motionSettings: MotionSettings,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    /**
     * editable-profile-name spec (D1/US-1): the stored display name, as its own [StateFlow] rather
     * than folded into [TasksUiState] (AC-10) — the name is not task-list state, and mixing it in
     * would rebuild the sealed state on every unrelated preference emission (e.g. a theme change).
     * Hilt resolves [userPreferencesRepository] from `di/StorageModule.kt` with no new wiring.
     */
    val userName: StateFlow<String> = userPreferencesRepository.userPreferences
        .map { it.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * Feature 04b: the search field's raw text, as its **own** [MutableStateFlow] — deliberately
     * separate from [arrangement]/[_priorityFilter] below. [TasksScreen]'s field reads [query]
     * directly, so every keystroke is reflected on screen the instant it happens (US-1); only the
     * *derived filtering* downstream of [debouncedQuery] waits for a pause in typing. Keeping the
     * query and the sort/group/filter state as separate `StateFlow`s is what lets one be debounced
     * while the others stay immediate.
     */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * `persisted-list-preferences` (D2/D3): the durable half of the list's arrangement — sort
     * field, direction and grouping axis — read straight from
     * [UserPreferencesRepository.userPreferences]. `null` until the very first DataStore emission
     * arrives; [uiState] below treats `null` as [TasksUiState.Loading] rather than ever rendering
     * a default-valued frame that would then jump to the restored one (D3's "unrepresentable, not
     * merely unlikely" invariant). There is deliberately **no** local echo `MutableStateFlow` here
     * (D2) — DataStore is the single source of truth, and each setter below writes through
     * [userPreferencesRepository] instead of mutating a parallel value.
     */
    private val arrangement: StateFlow<TaskListCriteria?> =
        userPreferencesRepository.userPreferences
            .map { it.taskListArrangement }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * D1/D2: the priority filter *hides* tasks rather than re-arranging them, so — unlike sort
     * field/direction/group axis above — it is never persisted and stays a plain in-memory
     * `MutableStateFlow`, the same role [_query] already plays for the (also unpersisted) text
     * search. [uiState] below reassembles a full [TaskListCriteria] from [arrangement] plus this
     * at the point of use.
     */
    private val _priorityFilter = MutableStateFlow<Set<Priority>>(emptySet())

    /**
     * The full [TaskListCriteria] [TasksScreen]'s control row (chips) renders, reassembled from
     * [arrangement] + [_priorityFilter] — kept as its **own** `StateFlow`, separate from [uiState],
     * so the chips can reflect a sort/group/filter change without waiting on [debouncedQuery] or a
     * fresh [uiTasksFlow] tick. `null` exactly when [arrangement] is (D3's invariant): [TasksRoute]
     * passes this straight through to [TasksScreen] as `criteria: TaskListCriteria?`, which renders
     * the control row only when it is non-null — precisely when the list itself is no longer
     * [TasksUiState.Loading].
     */
    val criteria: StateFlow<TaskListCriteria?> =
        combine(arrangement, _priorityFilter) { arr, filter -> arr?.copy(priorityFilter = filter) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
     * switched (via [flatMapLatest]) to a [countdownTicker] while any task is running, then mapped
     * to [TaskUiModel]s. Feature 04b adds operators **on top of** this Flow (see [uiState] below)
     * rather than opening a second stream toward Room — there is still only ever one read of the
     * task list.
     *
     * `reduce-motion` spec (D4): [combine]d with [MotionSettings.reduceMotion] so the ticker's
     * interval is recomputed via [tickIntervalFor] whenever *either* the task list or the
     * reduce-motion flag changes — a system-setting toggle mid-session takes effect on the very
     * next emission, no app restart needed. The existing "nothing running → no ticker at all"
     * optimisation is preserved exactly, in both motion modes: reduced motion never *starts* a
     * ticker that would otherwise stay off, it only changes the interval of one that runs anyway.
     */
    private val uiTasksFlow: Flow<List<TaskUiModel>> =
        combine(repository.observeTasks(), motionSettings.reduceMotion) { tasks, reduceMotion ->
            tasks to reduceMotion
        }
            .flatMapLatest { (tasks, reduceMotion) ->
                if (tasks.any { it.isRunning }) {
                    val interval = tickIntervalFor(reduceMotion, tasks, System.currentTimeMillis())
                    countdownTicker(interval).map { tasks }
                } else {
                    flowOf(tasks)
                }
            }
            .map { tasks -> tasks.toTaskUiModels() }

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
     * from [debouncedQuery], or a sort/group/filter change from [arrangement]/[_priorityFilter] —
     * always pairing each source's *latest* value, never a stale one. That is exactly US-3's requirement: the visible
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
        combine(uiTasksFlow, debouncedQuery, arrangement, _priorityFilter) { uiTasks, settledQuery, arr, filter ->
            // D3's invariant: arrangement == null is Loading, full stop — never a default-valued
            // Content that would jump to the restored arrangement a moment later.
            if (arr == null) {
                TasksUiState.Loading
            } else {
                shapeToUiState(uiTasks, settledQuery, arr.copy(priorityFilter = filter))
            }
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

    /**
     * US-2: switches which field the list is sorted by, keeping the current direction.
     * `persisted-list-preferences` (D2): writes through [userPreferencesRepository] instead of a
     * local `MutableStateFlow` — the new value reaches [uiState] once DataStore re-emits.
     * [arrangement]'s current value stands in for "keeping the current direction/group axis"
     * since there is no local echo to read; if it is still `null` (DataStore has not answered
     * yet), the chip cannot have been visible to tap in the first place (D3's invariant), so this
     * falls back to [TaskListCriteria]'s own defaults rather than crash.
     */
    fun onSortFieldChange(field: TaskSortField) {
        val current = arrangement.value ?: TaskListCriteria()
        viewModelScope.launch {
            userPreferencesRepository.saveTaskListArrangement(current.copy(sortField = field))
        }
    }

    /** US-2: flips ascending ↔ descending for whichever field is currently selected. Writes
     *  through the repository — see [onSortFieldChange]'s KDoc for the same reasoning. */
    fun onToggleSortDirection() {
        val current = arrangement.value ?: TaskListCriteria()
        val flipped = when (current.direction) {
            SortDirection.Ascending -> SortDirection.Descending
            SortDirection.Descending -> SortDirection.Ascending
        }
        viewModelScope.launch {
            userPreferencesRepository.saveTaskListArrangement(current.copy(direction = flipped))
        }
    }

    /**
     * US-3 (`priority-sorting`): switches the group axis. Called with [TaskGroupAxis.Urgency] or
     * [TaskGroupAxis.Priority] when the corresponding chip is tapped, and with
     * [TaskGroupAxis.None] when the currently-active chip is tapped again — [TasksScreen] decides
     * which of the three to pass, this setter just applies it. Writes through the repository —
     * see [onSortFieldChange]'s KDoc for the same reasoning.
     */
    fun onGroupAxisChange(axis: TaskGroupAxis) {
        val current = arrangement.value ?: TaskListCriteria()
        viewModelScope.launch {
            userPreferencesRepository.saveTaskListArrangement(current.copy(groupAxis = axis))
        }
    }

    /**
     * D5/US-2 (`priority-sorting`): flips [priority]'s membership in the filter set — the same
     * "flip a `Set` member via `.copy(...)`" pattern every other multi-select control in this
     * codebase would use. An empty set (every priority absent) means "no filter", so tapping the
     * last active chip off returns to showing everything. D1: the priority filter is never
     * persisted, so this only mutates [_priorityFilter] in memory.
     */
    fun onPriorityFilterToggle(priority: Priority) {
        val current = _priorityFilter.value
        val updated = if (priority in current) current - priority else current + priority
        _priorityFilter.value = updated
    }

    /**
     * US-2 (`priority-sorting`, R2): [TasksUiState.NoResults]' clear action must never strand the
     * user in a state whose only escape leaves a filter active — clears **both** the text query
     * and the priority filter in one call, unlike [onQueryChange]/[onPriorityFilterToggle] which
     * each touch only their own slice of state. D1: the persisted arrangement (sort/group) is
     * deliberately left untouched — clearing filters is not the same action as changing how the
     * list is arranged.
     */
    fun onClearFilters() {
        _query.value = ""
        _priorityFilter.value = emptySet()
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
     * [now] follows [toTaskUiModels]'s existing convention (a defaulted parameter rather than an
     * inline [System.currentTimeMillis] call) so a test can pin the exact instant.
     */
    fun toggleComplete(task: Task, now: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            repository.saveTask(task.copy(completedAt = if (task.completedAt == null) now else null))
        }
    }
}
