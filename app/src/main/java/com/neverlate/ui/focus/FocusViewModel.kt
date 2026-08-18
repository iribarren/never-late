package com.neverlate.ui.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.domain.tasks.FocusProgress
import com.neverlate.domain.tasks.FocusSession
import com.neverlate.domain.tasks.focusProgressFor
import com.neverlate.domain.tasks.focusRowsFor
import com.neverlate.domain.tasks.isFocusSessionActive
import com.neverlate.ui.tasks.FOCUS_EXIT_CODE_LENGTH
import com.neverlate.ui.tasks.TaskUiModel
import com.neverlate.ui.tasks.toTaskUiModels
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whole seconds the "No recuerdo el código" reveal counts down for (D3) — 60s, not a security
 *  control (D2), only long enough not to be the default path. */
private const val CODE_REVEAL_COUNTDOWN_SECONDS = 60

/**
 * Everything [FocusScreen] renders. [Loading] covers both "the session hasn't loaded from
 * DataStore yet" and "the session has just ended/expired" — either way there is nothing left to
 * show here, and `AppNavHost`'s routing (D4) means this composable is never reached at all unless
 * an active session exists, so [Loading] is expected to be near-instantaneous in practice, exactly
 * like [com.neverlate.ui.tasks.TasksUiState.Loading].
 */
sealed interface FocusUiState {
    data object Loading : FocusUiState

    data class Content(
        /** Roster members only (D1), soonest-deadline-first, completed ones sunk last — see
         *  [focusRowsFor]. The first non-completed row is the screen's visually dominant "current
         *  task" (AC-V2); [FocusScreen] derives that split itself. */
        val rows: List<TaskUiModel>,
        val progress: FocusProgress,
        val exitPanel: ExitPanelUiState,
        /**
         * Modo Foco blindaje (`docs/specs/2026-08-18-focus-mode-shielding.md`, D7/AC-V4): whether
         * the Do-Not-Disturb measure is **verified** active right now, for the in-session
         * indicator. Derived from the write-ahead receipt's presence
         * ([com.neverlate.data.UserPreferences.focusShieldPriorFilter] `!= null`) rather than the
         * entry dialog's requested option — the receipt is only ever written once
         * [FocusShieldController.applyDoNotDisturb] has actually succeeded (see
         * [com.neverlate.ui.focus.applyFocusShieldOnSessionStart]), so its presence *is* the
         * verified signal, with no second query needed.
         */
        val doNotDisturbActive: Boolean,
        /**
         * D8: whether the session's own choice was "Pantalla siempre encendida" — [FocusScreen]
         * reads this to decide whether to apply the immersive/keep-screen-on `DisposableEffect` at
         * all. Sourced from the same [com.neverlate.data.UserPreferences.focusShieldOptions] the
         * entry dialog just persisted at session start (D4's start sequence saves it first, before
         * anything else), never re-asked at every recomposition.
         */
        val keepScreenOn: Boolean,
    ) : FocusUiState
}

/**
 * The exit panel's own render state (US-3, D9). [codeFieldVisible] is `false` exactly when the
 * stored code is blank (D6 — a session whose code was lost is not a session you cannot leave), in
 * which case [codeSatisfied] is unconditionally `true`. [slideEnabled] is `tasksSatisfied &&
 * codeSatisfied` — the single source of truth both the drag gesture and its
 * `CustomAccessibilityAction` read (D9: never two different gates for the two input paths).
 */
data class ExitPanelUiState(
    val isOpen: Boolean,
    val enteredCode: String,
    val codeFieldVisible: Boolean,
    val tasksSatisfied: Boolean,
    val codeSatisfied: Boolean,
    val slideEnabled: Boolean,
    /** Whole seconds left in the "No recuerdo el código" countdown, or `null` while it is not
     *  running (not yet requested, or already finished). */
    val revealSecondsRemaining: Int?,
    /** The stored code in plain text, once the countdown above has reached zero — `null` until then. */
    val revealedCode: String?,
    val showAbandonConfirm: Boolean,
)

/** The two honest ways a session can end (D3) — surfaced once, via [FocusViewModel.exitEvents],
 *  and deliberately never persisted (session outcome history is Out of Scope). */
sealed interface FocusExitEvent {
    data class Completed(val completedCount: Int) : FocusExitEvent
    data object Abandoned : FocusExitEvent
}

/** [ExitPanelUiState.revealSecondsRemaining]/[ExitPanelUiState.revealedCode]'s ViewModel-private
 *  source of truth — see D5: this is deliberately [ViewModel]-only state, not `SavedStateHandle`. */
private sealed interface RevealState {
    data object Hidden : RevealState
    data class CountingDown(val secondsRemaining: Int) : RevealState
    data object Revealed : RevealState
}

/** The four locally-owned pieces of exit-panel UI state (D5), combined into one value before being
 *  paired with the live session/task data in [FocusViewModel.uiState]. */
private data class ExitPanelInputs(
    val isOpen: Boolean,
    val enteredCode: String,
    val reveal: RevealState,
    val showAbandonConfirm: Boolean,
)

/**
 * Modo Foco's screen ViewModel (`docs/specs/2026-08-18-focus-mode.md`). Combines the live
 * [TaskRepository.observeTasks] stream with the stored [FocusSession] from
 * [UserPreferencesRepository.userPreferences] — the roster (D1) stays frozen, but every roster
 * member's completed/deleted state is always read fresh. All exit-panel transient state (open/
 * closed, typed code, reveal countdown, abandon confirmation) lives here only (D5) — no
 * `SavedStateHandle` anywhere in this feature, deliberately: see the spec's D5 for why that middle
 * lifecycle tier is the wrong fit for state this cheap to lose.
 *
 * D10: task completion goes through [repository]`.saveTask` with the **exact** call
 * [com.neverlate.ui.tasks.TasksViewModel.toggleComplete] already uses — no second write path, so
 * completing a task here still refreshes the widget/notification, cancels reminders and enqueues
 * the outbox row via the same four-layer decorator chain.
 */
@HiltViewModel
class FocusViewModel @Inject constructor(
    private val repository: TaskRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val focusShieldController: FocusShieldController,
) : ViewModel() {

    private val _exitPanelOpen = MutableStateFlow(false)
    private val _enteredCode = MutableStateFlow("")
    private val _reveal = MutableStateFlow<RevealState>(RevealState.Hidden)
    private val _showAbandonConfirm = MutableStateFlow(false)
    private var revealJob: Job? = null

    private val _exitEvents = Channel<FocusExitEvent>(Channel.BUFFERED)

    /** One-shot exit outcomes (D3) — [FocusScreen]'s route collects this to navigate back to Tasks
     *  and stash the outcome for the snackbar (see `AppNavHost`'s `Routes.FOCUS` composable). */
    val exitEvents: Flow<FocusExitEvent> = _exitEvents.receiveAsFlow()

    private val sessionFlow: StateFlow<FocusSession?> = userPreferencesRepository.userPreferences
        .map { it.focusSession }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Modo Foco blindaje (D7): the write-ahead receipt's presence, doubling as the verified
     *  "is Do Not Disturb on right now" signal the in-session indicator reads — see
     *  [FocusUiState.Content.doNotDisturbActive]'s KDoc. */
    private val doNotDisturbActiveFlow: StateFlow<Boolean> = userPreferencesRepository.userPreferences
        .map { it.focusShieldPriorFilter != null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** D8: the session's own "keep screen on" choice — see [FocusUiState.Content.keepScreenOn]. */
    private val keepScreenOnFlow: StateFlow<Boolean> = userPreferencesRepository.userPreferences
        .map { it.focusShieldOptions.keepScreenOn }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val exitPanelInputs: StateFlow<ExitPanelInputs> = combine(
        _exitPanelOpen, _enteredCode, _reveal, _showAbandonConfirm,
    ) { isOpen, enteredCode, reveal, showAbandonConfirm ->
        ExitPanelInputs(isOpen, enteredCode, reveal, showAbandonConfirm)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ExitPanelInputs(isOpen = false, enteredCode = "", reveal = RevealState.Hidden, showAbandonConfirm = false),
    )

    val uiState: StateFlow<FocusUiState> = combine(
        sessionFlow, repository.observeTasks(), exitPanelInputs, doNotDisturbActiveFlow, keepScreenOnFlow,
    ) { session, tasks, panel, doNotDisturbActive, keepScreenOn ->
        buildUiState(session, tasks, panel, doNotDisturbActive, keepScreenOn)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FocusUiState.Loading)

    private fun buildUiState(
        session: FocusSession?,
        tasks: List<Task>,
        panel: ExitPanelInputs,
        doNotDisturbActive: Boolean,
        keepScreenOn: Boolean,
        now: Long = System.currentTimeMillis(),
    ): FocusUiState {
        // D7/D6: an inactive (expired, absent, or fail-open-null) session shows nothing here —
        // AppNavHost's own routing (D4) means this composable is never reached without an active
        // session in the first place; this is only the belt-and-braces fallback while the pop
        // back to Tasks (triggered elsewhere) is in flight.
        if (!isFocusSessionActive(session, now) || session == null) return FocusUiState.Loading

        val uiTasks = tasks.toTaskUiModels(now)
        val rows = focusRowsFor(uiTasks, session.roster)
        val progress = focusProgressFor(uiTasks, session.roster)
        val tasksSatisfied = progress.isComplete
        val storedCode = session.exitCode
        val codeFieldVisible = storedCode.isNotBlank()
        val codeSatisfied = storedCode.isBlank() || panel.enteredCode == storedCode

        return FocusUiState.Content(
            rows = rows,
            progress = progress,
            exitPanel = ExitPanelUiState(
                isOpen = panel.isOpen,
                enteredCode = panel.enteredCode,
                codeFieldVisible = codeFieldVisible,
                tasksSatisfied = tasksSatisfied,
                codeSatisfied = codeSatisfied,
                slideEnabled = tasksSatisfied && codeSatisfied,
                revealSecondsRemaining = (panel.reveal as? RevealState.CountingDown)?.secondsRemaining,
                revealedCode = if (panel.reveal is RevealState.Revealed) storedCode else null,
                showAbandonConfirm = panel.showAbandonConfirm,
            ),
            doNotDisturbActive = doNotDisturbActive,
            keepScreenOn = keepScreenOn,
        )
    }

    /** D8/AC-16: the system back gesture opens the panel; a second press (or the persistent bottom
     *  "Salir del Modo Foco" button, or the panel's own scrim) closes it again — it never pops the
     *  destination either way. */
    fun onExitPanelToggle() {
        _exitPanelOpen.value = !_exitPanelOpen.value
    }

    fun onExitPanelDismiss() {
        _exitPanelOpen.value = false
    }

    /** AC-18: only digits, capped at [FOCUS_EXIT_CODE_LENGTH] — same filtering rule the entry
     *  dialog's own field uses. */
    fun onCodeChange(input: String) {
        _enteredCode.value = input.filter { it.isDigit() }.take(FOCUS_EXIT_CODE_LENGTH)
    }

    /**
     * D3/AC-24: starts (or, if already running/finished, is a no-op — idempotent against a double
     * tap) a visible 60-second countdown, after which the stored code is shown in plain text. The
     * countdown runs from **when the person asks**, never from session start (see the spec's D3 for
     * why anchoring it to start would trap a forgetful person for the first N minutes).
     */
    fun onForgetCodeClick() {
        if (_reveal.value !is RevealState.Hidden) return
        revealJob?.cancel()
        revealJob = viewModelScope.launch {
            var secondsRemaining = CODE_REVEAL_COUNTDOWN_SECONDS
            while (secondsRemaining > 0) {
                _reveal.value = RevealState.CountingDown(secondsRemaining)
                delay(1_000)
                secondsRemaining--
            }
            _reveal.value = RevealState.Revealed
        }
    }

    /**
     * The dignified exit (D3): only takes effect while [ExitPanelUiState.slideEnabled] is true in
     * the *current* [uiState] — both the drag gesture and its `CustomAccessibilityAction` call this
     * same function, so the two input paths can never reach a different outcome (D9). Ends the
     * session as **completed** and emits [FocusExitEvent.Completed] with the final count.
     */
    fun onSlideComplete() {
        val state = uiState.value
        if (state !is FocusUiState.Content || !state.exitPanel.slideEnabled) return
        val completedCount = state.progress.done
        viewModelScope.launch {
            endSessionWithShieldTeardown()
            _exitEvents.send(FocusExitEvent.Completed(completedCount))
        }
    }

    /** Opens the confirmation dialog for the emergency exit — the button itself is always enabled
     *  (D3); this only shows the one required confirmation, never a gate. */
    fun onAbandonClick() {
        _showAbandonConfirm.value = true
    }

    fun onAbandonCancel() {
        _showAbandonConfirm.value = false
    }

    /** D3: ends the session as **abandoned**, modifying no task — no write to [repository] happens
     *  anywhere on this path, only [userPreferencesRepository.endFocusSession]. */
    fun onAbandonConfirm() {
        _showAbandonConfirm.value = false
        viewModelScope.launch {
            endSessionWithShieldTeardown()
            _exitEvents.send(FocusExitEvent.Abandoned)
        }
    }

    /**
     * Modo Foco blindaje (D6/AC-15): both deliberate exits run through this one function —
     * [FocusShieldController.restore] (the Do-Not-Disturb decision, D5) and
     * [FocusShieldController.cancelBackstop] (D6 — a completed session needs no 12h backstop),
     * **both before** [UserPreferencesRepository.endFocusSession]. `sessionActive = false` here is
     * correct precisely because this function only ever runs once the exit has already been
     * decided (the ritual's gate passed, or abandon confirmed) — the session is over in every
     * sense but the DataStore write, which happens last on purpose (D4's end sequence). Screen
     * pinning's own `stopLockTask()` is Activity-scoped and therefore **not** here — see
     * [FocusRoute]'s `exitEvents` collector, which runs it before navigating away (AC-22).
     */
    private suspend fun endSessionWithShieldTeardown() {
        focusShieldController.restore(sessionActive = false)
        focusShieldController.cancelBackstop()
        userPreferencesRepository.endFocusSession()
    }

    /**
     * D10: the exact call [com.neverlate.ui.tasks.TasksViewModel.toggleComplete] uses —
     * `completedAt = now` if the task was pending, `null` (undo) if it was already done — so
     * completing a task from Modo Foco goes through the same [TaskRepository.saveTask] path,
     * refreshing the widget/notification and enqueueing the outbox row with no focus-specific write
     * code (AC-25). [now] is a defaulted parameter, not an inline clock read, so a test can pin it.
     */
    fun toggleComplete(task: Task, now: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            repository.saveTask(task.copy(completedAt = if (task.completedAt == null) now else null))
        }
    }
}
