package com.neverlate.ui.focus

import android.app.Activity
import android.app.ActivityManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neverlate.R
import com.neverlate.data.tasks.Task
import com.neverlate.domain.tasks.FocusProgress
import com.neverlate.domain.tasks.deadlineProgressFor
import com.neverlate.domain.tasks.urgencyLevelFor
import com.neverlate.ui.components.BrandIconChip
import com.neverlate.ui.components.MessageState
import com.neverlate.ui.components.ReadableWidthContainer
import com.neverlate.ui.components.brandedTopAppBarColors
import com.neverlate.ui.components.formatRemainingLabel
import com.neverlate.ui.tasks.TaskUiModel
import com.neverlate.ui.theme.colorForUrgency
import kotlin.math.roundToInt

/**
 * Stateful wrapper: obtains [FocusViewModel] via `hiltViewModel()`, following the same Route/
 * Screen split every other screen in this app uses. [onSessionCompleted]/[onSessionAbandoned] are
 * wired from `AppNavHost`'s `Routes.FOCUS` composable exactly like [com.neverlate.ui.tasks.TaskEditRoute]'s
 * `onSaved` — a one-shot navigation event, here fed by [FocusViewModel.exitEvents] rather than a
 * plain callback param, since the outcome (and, for a completed session, the count) is only known
 * once the ritual actually finishes.
 *
 * D8/AC-16: [BackHandler] is unconditionally enabled for the whole lifetime of this composable —
 * the system back gesture only ever opens (or toggles) the exit panel, it never pops this
 * destination. This is the repo's only other use of the idiom
 * ([com.neverlate.ui.articles.ArticlesListDetailPane]'s two-pane back handling) applied to a single
 * screen instead of a nested navigator.
 *
 * Modo Foco blindaje (`docs/specs/2026-08-18-focus-mode-shielding.md`) adds three things here, all
 * Activity-scoped (D1) and therefore living in this Route rather than [FocusViewModel]:
 * - **AC-21/D7**: [screenPinningActive] is read back from `ActivityManager.getLockTaskModeState()`
 *   once, on first composition — the `startLockTask()` *request* already happened in
 *   `AppNavHost`'s `onFocusClick`, right before navigating here, so by the time this composable
 *   runs the system already knows the answer (no receipt, only a query).
 * - **AC-22**: the `exitEvents` collector releases pinning (`stopLockTask()`, if verified active)
 *   **before** invoking [onSessionCompleted]/[onSessionAbandoned] — i.e. before navigating away.
 * - **D8/AC-26/AC-27**: a `DisposableEffect` applies immersive system bars (always recoverable by
 *   a swipe, `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`) and `FLAG_KEEP_SCREEN_ON` while
 *   [FocusUiState.Content.keepScreenOn] is true, reverting both in `onDispose` — so leaving this
 *   screen by *any* route (ritual, abandon, process death, back+overview while pinned) reverts
 *   them for free, with no receipt and no worker (D1).
 */
@Composable
fun FocusRoute(
    onSessionCompleted: (completedCount: Int) -> Unit,
    onSessionAbandoned: () -> Unit,
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
    viewModel: FocusViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity

    // AC-21/D7: verified once, right as this screen appears — see this function's KDoc.
    val screenPinningActive = remember {
        val activityManager = activity?.getSystemService(ActivityManager::class.java)
        activityManager?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    LaunchedEffect(Unit) {
        viewModel.exitEvents.collect { event ->
            // AC-22: release pinning before navigating away — Activity-scoped, so it cannot live
            // in FocusViewModel (see FocusViewModel.endSessionWithShieldTeardown's KDoc).
            val activityManager = activity?.getSystemService(ActivityManager::class.java)
            if (activityManager?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
                try {
                    activity?.stopLockTask()
                } catch (_: IllegalStateException) {
                    // D10: not actually pinned by the time this runs — nothing to release.
                }
            }
            when (event) {
                is FocusExitEvent.Completed -> onSessionCompleted(event.completedCount)
                FocusExitEvent.Abandoned -> onSessionAbandoned()
            }
        }
    }

    BackHandler(enabled = true) { viewModel.onExitPanelToggle() }

    // D8: composable-scoped, no undo machinery needed (D1) — applied here, never
    // MainActivity.onCreate (which stays limited to enableEdgeToEdge()), and reverted on dispose.
    val keepScreenOn = (uiState as? FocusUiState.Content)?.keepScreenOn ?: false
    DisposableEffect(activity, keepScreenOn) {
        val window = activity?.window
        if (window == null || !keepScreenOn) return@DisposableEffect onDispose {}

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        // AC-27/D8: a swipe must always bring the bars back temporarily — a hard requirement, not
        // a default, so nobody is trapped behind a hidden status bar.
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    FocusScreen(
        uiState = uiState,
        screenPinningActive = screenPinningActive,
        onToggleComplete = viewModel::toggleComplete,
        onExitButtonClick = viewModel::onExitPanelToggle,
        onPanelDismiss = viewModel::onExitPanelDismiss,
        onCodeChange = viewModel::onCodeChange,
        onForgetCodeClick = viewModel::onForgetCodeClick,
        onSlideComplete = viewModel::onSlideComplete,
        onAbandonClick = viewModel::onAbandonClick,
        onAbandonCancel = viewModel::onAbandonCancel,
        onAbandonConfirm = viewModel::onAbandonConfirm,
        widthSizeClass = widthSizeClass,
        modifier = modifier,
    )
}

/**
 * Stateless composable: renders a [FocusUiState] and reports intent through callbacks only, same
 * pattern as every other screen in this app.
 *
 * AC-V1: the top bar carries [FocusUiState.Content.progress] ("3 de 7") and nothing else — no
 * `navigationIcon`, no `actions` — nothing in the chrome invites the person elsewhere. This
 * composable as a whole is deliberately **not** wrapped in `ReadableWidthContainer` by its caller
 * (`AppNavHost`, D4) — the top bar and the bottom exit action span the full window at every width.
 * AC-V10 draws a narrower line than "no `ReadableWidthContainer` anywhere": the task **rows**
 * still need a comfortable measure on a wide window, or 30+ rows at 1200dp become an unreadable
 * wall of edge-to-edge text. [FocusContent] below reuses `ReadableWidthContainer` internally,
 * around just the row list/`MessageState` — never around the top bar or the exit button — so the
 * *surface* spans the window while the *rows* stay measured, exactly as AC-V10 requires.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(
    uiState: FocusUiState,
    screenPinningActive: Boolean,
    onToggleComplete: (Task) -> Unit,
    onExitButtonClick: () -> Unit,
    onPanelDismiss: () -> Unit,
    onCodeChange: (String) -> Unit,
    onForgetCodeClick: () -> Unit,
    onSlideComplete: () -> Unit,
    onAbandonClick: () -> Unit,
    onAbandonCancel: () -> Unit,
    onAbandonConfirm: () -> Unit,
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.focus_title))
                            if (uiState is FocusUiState.Content) {
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.focus_progress_state_description,
                                        uiState.progress.total,
                                        uiState.progress.done,
                                        uiState.progress.total,
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    },
                    colors = brandedTopAppBarColors(),
                )
            },
            bottomBar = {
                // The only action in the chrome besides per-row checkboxes (mockup slice, AC-V3):
                // opens the exit panel, exactly like the system back gesture (D8) and MessageState's
                // own action below. AC-V6: navigationBarsPadding() keeps this button clear of the
                // gesture area while immersive — nothing needed to leave the session is ever hidden
                // by the immersive state.
                if (uiState is FocusUiState.Content) {
                    Surface(tonalElevation = 3.dp, modifier = Modifier.navigationBarsPadding()) {
                        TextButton(
                            onClick = onExitButtonClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .minimumInteractiveComponentSize(),
                        ) {
                            Text(stringResource(R.string.focus_exit_button))
                        }
                    }
                }
            },
        ) { innerPadding ->
            when (uiState) {
                // Nothing to show yet — AppNavHost's own routing (D4) means this is expected to be
                // near-instantaneous; same "no one-frame flash" reasoning as TasksUiState.Loading.
                is FocusUiState.Loading -> Unit
                is FocusUiState.Content -> Column(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize(),
                ) {
                    FocusShieldIndicatorRow(
                        doNotDisturbActive = uiState.doNotDisturbActive,
                        screenPinningActive = screenPinningActive,
                    )
                    FocusContent(
                        state = uiState,
                        onToggleComplete = onToggleComplete,
                        onExitButtonClick = onExitButtonClick,
                        widthSizeClass = widthSizeClass,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (uiState is FocusUiState.Content && uiState.exitPanel.isOpen) {
            FocusExitPanel(
                exitPanel = uiState.exitPanel,
                progress = uiState.progress,
                onCodeChange = onCodeChange,
                onForgetCodeClick = onForgetCodeClick,
                onSlideComplete = onSlideComplete,
                onAbandonClick = onAbandonClick,
                onAbandonCancel = onAbandonCancel,
                onAbandonConfirm = onAbandonConfirm,
                onDismiss = onPanelDismiss,
            )
        }
    }
}

/**
 * Modo Foco blindaje (`docs/specs/2026-08-18-focus-mode-shielding.md`, D7/AC-V4/AC-V5/AC-36): the
 * compact, non-interactive strip directly under the top bar, listing only the **verified**-active
 * device measures — never merely what was requested (D7). "Pantalla siempre encendida" is
 * deliberately **not** shown here even when active: while looking at this very screen, the fact
 * that its own screen is on is not information — the indicator exists for the two measures whose
 * effects are otherwise invisible (US-6).
 *
 * Absent entirely (renders nothing) when neither measure is active (AC-V5) — an empty strip would
 * be visual noise on a screen whose entire premise is the absence of noise. Text **and** icon for
 * each active measure (AC-36) — never icon or colour alone.
 */
@Composable
private fun FocusShieldIndicatorRow(
    doNotDisturbActive: Boolean,
    screenPinningActive: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!doNotDisturbActive && !screenPinningActive) return

    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (doNotDisturbActive) {
                FocusShieldIndicatorChip(
                    icon = Icons.Filled.NotificationsOff,
                    label = stringResource(R.string.focus_shield_indicator_do_not_disturb),
                )
            }
            if (screenPinningActive) {
                FocusShieldIndicatorChip(
                    icon = Icons.Filled.PushPin,
                    label = stringResource(R.string.focus_shield_indicator_screen_pinning),
                )
            }
        }
    }
}

@Composable
private fun FocusShieldIndicatorChip(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            // Decorative: the label Text right next to it carries the same meaning for a screen
            // reader (AC-36 — text, not icon alone).
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * AC-V6: [state.progress]'s `isComplete` selects between the task list and
 * [ui/components/MessageState] — reused as-is, with the exit action as its own `actionLabel`/
 * `onAction` pair, so finishing the work and finishing the session read as one continuous motion.
 *
 * AC-V10: [ReadableWidthContainer] wraps **only** this content — never the top bar or the bottom
 * exit button, both of which stay full-width at every window size (see [FocusScreen]'s own KDoc) —
 * so the surface spans the window while the rows themselves keep a comfortable measure on a wide
 * window, exactly like `ReadableWidthContainer`'s other two single-pane consumers (Tasks, Settings),
 * just applied narrower than the whole screen here.
 */
@Composable
private fun FocusContent(
    state: FocusUiState.Content,
    onToggleComplete: (Task) -> Unit,
    onExitButtonClick: () -> Unit,
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
) {
    ReadableWidthContainer(widthSizeClass = widthSizeClass, modifier = modifier) {
        if (state.progress.isComplete) {
            MessageState(
                icon = Icons.AutoMirrored.Filled.Assignment,
                message = stringResource(R.string.focus_empty_message),
                actionLabel = stringResource(R.string.focus_exit_button),
                onAction = onExitButtonClick,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            FocusTaskList(
                rows = state.rows,
                onToggleComplete = onToggleComplete,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * AC-V2/AC-V3: the first non-completed row in [rows] (already soonest-deadline-first, completed
 * last — see [com.neverlate.domain.tasks.focusRowsFor]) is rendered visually dominant; every other
 * row is compact and uniform. The list scrolls independently under the fixed top bar and above the
 * fixed exit action (both live in [FocusScreen]'s `Scaffold` slots, outside this composable).
 */
@Composable
private fun FocusTaskList(
    rows: List<TaskUiModel>,
    onToggleComplete: (Task) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentTaskId = rows.firstOrNull { it.task.completedAt == null }?.task?.id
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(rows, key = { it.task.id }) { uiModel ->
            FocusTaskRow(
                uiModel = uiModel,
                isCurrent = uiModel.task.id == currentTaskId,
                onToggleComplete = { onToggleComplete(uiModel.task) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/**
 * D10: a **new** row, not [com.neverlate.ui.tasks.TaskRow] — that one carries start/pause/delete/
 * tap-to-edit affordances this screen must not offer (see the feature spec's D10 table). Every
 * token/helper inside it is reused: [BrandIconChip], [formatRemainingLabel], [deadlineProgressFor],
 * [colorForUrgency] — only the row shape itself is new, and only [isCurrent] (AC-V2) makes it larger.
 */
@Composable
private fun FocusTaskRow(
    uiModel: TaskUiModel,
    isCurrent: Boolean,
    onToggleComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val task = uiModel.task
    val isCompleted = task.completedAt != null

    val urgencyLevel by remember(uiModel.remainingMillis, uiModel.isTimedOut) {
        derivedStateOf { urgencyLevelFor(uiModel.remainingMillis, uiModel.isTimedOut) }
    }
    val progress by remember(uiModel.remainingMillis, uiModel.isTimedOut) {
        derivedStateOf {
            deadlineProgressFor(uiModel.remainingMillis, task.estimatedDurationMillis, uiModel.isTimedOut)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (isCurrent) 8.dp else 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(if (isCurrent) 24.dp else 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // AC-29: reuses the same checkbox content-description strings TaskRow already uses —
            // "mark done"/"completed" is one shared meaning across both surfaces.
            val toggleDescription = stringResource(
                if (isCompleted) R.string.tasks_completed_content_description else R.string.tasks_mark_done_content_description,
            )
            Checkbox(
                checked = isCompleted,
                onCheckedChange = { onToggleComplete() },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = toggleDescription },
            )

            BrandIconChip(icon = Icons.AutoMirrored.Filled.Assignment)

            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    text = task.title,
                    style = if (isCurrent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                    textDecoration = if (isCompleted) TextDecoration.LineThrough else null,
                    color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                )

                if (!isCompleted) {
                    val context = LocalContext.current
                    Text(
                        text = formatRemainingLabel(context, uiModel.remainingMillis),
                        style = if (isCurrent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium,
                        color = colorForUrgency(urgencyLevel),
                        modifier = Modifier.padding(top = if (isCurrent) 8.dp else 2.dp),
                    )
                    progress?.let { fraction ->
                        LinearProgressIndicator(
                            progress = { fraction },
                            color = colorForUrgency(urgencyLevel),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .height(if (isCurrent) 8.dp else 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The exit panel (US-3): a modal sheet drawn over the session, never a separate destination — a
 * dimmed scrim (tap to dismiss, [onDismiss]) behind a bottom-anchored [Surface] that scrolls its own
 * content ([androidx.compose.foundation.verticalScroll], AC-V9) rather than clipping at the largest
 * font scale. The sheet swallows its own taps so tapping inside it never falls through to the scrim.
 */
@Composable
private fun FocusExitPanel(
    exitPanel: ExitPanelUiState,
    progress: FocusProgress,
    onCodeChange: (String) -> Unit,
    onForgetCodeClick: () -> Unit,
    onSlideComplete: () -> Unit,
    onAbandonClick: () -> Unit,
    onAbandonCancel: () -> Unit,
    onAbandonConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(text = stringResource(R.string.focus_title), style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))

                ChecklistItem(
                    satisfied = exitPanel.tasksSatisfied,
                    label = pluralStringResource(
                        R.plurals.focus_exit_requirement_tasks,
                        progress.total,
                        progress.done,
                        progress.total,
                    ),
                )
                ChecklistItem(
                    satisfied = exitPanel.codeSatisfied,
                    label = stringResource(R.string.focus_exit_requirement_code),
                )
                ChecklistItem(
                    satisfied = exitPanel.slideEnabled,
                    label = stringResource(R.string.focus_exit_requirement_slide),
                )

                if (exitPanel.codeFieldVisible) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = exitPanel.enteredCode,
                        onValueChange = onCodeChange,
                        label = { Text(stringResource(R.string.focus_entry_dialog_code_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    when {
                        exitPanel.revealedCode != null -> Text(
                            text = stringResource(R.string.focus_exit_code_revealed_label, exitPanel.revealedCode),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        exitPanel.revealSecondsRemaining != null -> Text(
                            text = stringResource(
                                R.string.focus_exit_countdown_label,
                                exitPanel.revealSecondsRemaining,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> TextButton(
                            onClick = onForgetCodeClick,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) { Text(stringResource(R.string.focus_exit_forgot_code_button)) }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                FocusSlideToUnlock(
                    tasksSatisfied = exitPanel.tasksSatisfied,
                    codeSatisfied = exitPanel.codeSatisfied,
                    onComplete = onSlideComplete,
                )

                Spacer(modifier = Modifier.height(24.dp))
                TextButton(
                    onClick = onAbandonClick,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize(),
                ) { Text(stringResource(R.string.focus_abandon_button)) }
            }
        }
    }

    if (exitPanel.showAbandonConfirm) {
        AbandonConfirmDialog(onConfirm = onAbandonConfirm, onDismiss = onAbandonCancel)
    }
}

/** A live checklist row (US-3): the same satisfied/unsatisfied iconography as the row checkboxes,
 *  decorative (the [label] text carries the same meaning for a screen reader). */
@Composable
private fun ChecklistItem(satisfied: Boolean, label: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(vertical = 4.dp),
    ) {
        Icon(
            imageVector = if (satisfied) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (satisfied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Fraction of the track's travel the thumb must cross for a drag release to count as "complete"
 *  (D3's dignified exit) — comfortably short of the full track so the gesture stays forgiving. */
private const val SLIDE_COMPLETION_THRESHOLD = 0.9f

/**
 * D9: the slide-to-unlock bar. [enabled] (`tasksSatisfied && codeSatisfied`) gates **both** input
 * paths identically — the drag itself (via [Modifier.draggable], only attached while enabled) and
 * the [CustomAccessibilityAction] (only present in `customActions` while enabled) — so a screen
 * reader user is never offered a shortcut past the gate, nor left with no way through it (AC-26).
 * [Role.Button] plus a [contentDescription]/[stateDescription] pair (naming the missing prerequisite
 * while inert, AC-28) make the whole bar reachable and legible under TalkBack. The track/thumb are
 * both ≥48dp (AC-30), and the lock/unlock icon plus the state text distinguish enabled from inert
 * without relying on color alone (AC-V7).
 */
@Composable
private fun FocusSlideToUnlock(
    tasksSatisfied: Boolean,
    codeSatisfied: Boolean,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = tasksSatisfied && codeSatisfied
    val trackHeight = 56.dp
    val thumbSize = 48.dp
    val density = LocalDensity.current
    val thumbSizePx = with(density) { thumbSize.toPx() }

    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    var offsetPx by remember { mutableFloatStateOf(0f) }
    val maxOffsetPx = (trackWidthPx - thumbSizePx).coerceAtLeast(0f)

    // Disabling the gate (e.g. the code just went wrong, or a roster task un-checked) always
    // snaps the thumb back — an armed-but-now-invalid drag must never linger (D9's "same gate,
    // every time" guarantee).
    LaunchedEffect(enabled) { if (!enabled) offsetPx = 0f }

    val stateDescriptionText = when {
        !tasksSatisfied -> stringResource(R.string.focus_slide_state_disabled_tasks)
        !codeSatisfied -> stringResource(R.string.focus_slide_state_disabled_code)
        else -> stringResource(R.string.focus_slide_state_enabled)
    }
    val contentDescriptionText = stringResource(R.string.focus_slide_content_description)
    val actionLabel = stringResource(R.string.focus_slide_action_label)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .onGloballyPositioned { coordinates -> trackWidthPx = coordinates.size.width.toFloat() }
            .clip(RoundedCornerShape(trackHeight / 2))
            .background(
                if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            )
            .semantics {
                contentDescription = contentDescriptionText
                stateDescription = stateDescriptionText
                role = Role.Button
                if (enabled) {
                    customActions = listOf(CustomAccessibilityAction(actionLabel) { onComplete(); true })
                }
            },
    ) {
        Text(
            text = stringResource(R.string.focus_exit_requirement_slide),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.align(Alignment.Center),
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .padding(4.dp)
                .size(thumbSize)
                .clip(CircleShape)
                .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                .then(
                    if (enabled) {
                        Modifier.draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                offsetPx = (offsetPx + delta).coerceIn(0f, maxOffsetPx)
                            },
                            onDragStopped = {
                                if (maxOffsetPx > 0f && offsetPx / maxOffsetPx >= SLIDE_COMPLETION_THRESHOLD) {
                                    onComplete()
                                } else {
                                    offsetPx = 0f
                                }
                            },
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (enabled) Icons.Filled.LockOpen else Icons.Filled.Lock,
                // Decorative: contentDescription/stateDescription on the outer Box already say
                // everything a screen reader needs about this control.
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AbandonConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.focus_abandon_confirm_title)) },
        text = { Text(stringResource(R.string.focus_abandon_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.focus_abandon_confirm_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.focus_abandon_cancel_button)) }
        },
    )
}
