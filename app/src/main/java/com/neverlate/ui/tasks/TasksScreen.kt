package com.neverlate.ui.tasks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neverlate.R
import com.neverlate.data.sync.SyncStatus
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.data.tasks.durationParts
import com.neverlate.data.tasks.formatDeadlineForDisplay
import com.neverlate.data.tasks.formatRemaining
import com.neverlate.domain.tasks.ShapedTaskList
import com.neverlate.domain.tasks.SortDirection
import com.neverlate.domain.tasks.TaskListCriteria
import com.neverlate.domain.tasks.TaskSortField
import com.neverlate.domain.tasks.UrgencyLevel
import com.neverlate.domain.tasks.deadlineProgressFor
import com.neverlate.domain.tasks.urgencyLevelFor
import com.neverlate.ui.components.BrandIconChip
import com.neverlate.ui.components.MessageState
import com.neverlate.ui.components.brandedTopAppBarColors
import com.neverlate.ui.navigation.AppViewModelFactory
import com.neverlate.ui.notification.RequestNotificationPermissionEffect
import com.neverlate.ui.theme.NeverLateExtras
import com.neverlate.ui.theme.NeverLateTheme
import java.text.NumberFormat

/**
 * [SavedStateHandle] key AppNavHost uses to pass "a task was just created" back onto the Tasks
 * destination's own back stack entry, from the create-only Task Edit destination's `onSaved` (see
 * `AppNavHost`'s `Routes.TASK_EDIT` composable). This is the standard Navigation Compose way to
 * pass a one-shot result back to the previous screen: unlike [TaskEditViewModel]'s `isSaved` (read
 * once by a composable that is about to be disposed anyway), the Tasks screen stays alive, so its
 * "task created" signal needs to survive on the navigation back stack itself, not just in a
 * ViewModel that only [com.neverlate.ui.tasks.TaskEditScreen] ever sees.
 */
const val TASK_CREATED_RESULT_KEY = "taskCreated"

/**
 * Stateful wrapper: obtains [TasksViewModel] (via [AppViewModelFactory]) and forwards its state
 * to the stateless [TasksScreen], following the same route/screen split used for Articles (see
 * [com.neverlate.ui.articles.ArticlesRoute]).
 *
 * [taskCreatedHandle] (feature 17, US-3) is Tasks' own [SavedStateHandle], collected as a
 * one-shot event to show a "task created" [androidx.compose.material3.Snackbar] exactly once:
 * [androidx.lifecycle.SavedStateHandle.getStateFlow] re-emits the *current* value on every
 * (re)collection (e.g. after a configuration change), so simply reading it would re-show the
 * snackbar on rotation — the fix is to immediately write it back to `false` the moment `true` is
 * observed, "consuming" it, so a later re-collection only ever sees `false` again.
 *
 * [onBack] is `null` when Tasks is reached as a top-level bottom-bar tab (feature 18) — there is
 * no back arrow to show in that case, since the bar itself is the way to leave this screen (and
 * Tasks is also the app's landing destination, so there is nowhere "back" would go anyway).
 */
@Composable
fun TasksRoute(
    taskRepository: TaskRepository,
    onAddTaskClick: () -> Unit,
    onTaskClick: (Long) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: TasksViewModel = viewModel(factory = AppViewModelFactory(taskRepository = taskRepository)),
    taskCreatedHandle: SavedStateHandle? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val criteria by viewModel.criteria.collectAsStateWithLifecycle()
    // Feature 04b: collected separately from criteria above — the field reads this StateFlow
    // directly so every keystroke shows up immediately, while criteria's sort/group values are
    // the only ones still bundled together (see TasksViewModel's KDoc).
    val query by viewModel.query.collectAsStateWithLifecycle()
    // Ask for the POST_NOTIFICATIONS permission (Android 13+) in context, the first time the user
    // reaches their tasks — that is when "also show these on the lock screen" is meaningful. No-op
    // below Android 13, and denial degrades gracefully (see the effect's KDoc / feature 06).
    RequestNotificationPermissionEffect()

    // Owned here (not inside TasksScreen) so this Route's LaunchedEffect below and the Scaffold's
    // SnackbarHost in TasksScreen share the exact same instance, per the feature spec's approved
    // decision (post-save navigation already lands here, so this is where the confirmation
    // belongs).
    val snackbarHostState = remember { SnackbarHostState() }
    val taskCreatedMessage = stringResource(R.string.tasks_task_created_snackbar)

    if (taskCreatedHandle != null) {
        LaunchedEffect(taskCreatedHandle) {
            taskCreatedHandle.getStateFlow(TASK_CREATED_RESULT_KEY, false).collect { created ->
                if (created) {
                    // Consume immediately: from this point on the handle reads false again, so a
                    // later recomposition (e.g. a rotation) does not replay this snackbar.
                    taskCreatedHandle[TASK_CREATED_RESULT_KEY] = false
                    snackbarHostState.showSnackbar(taskCreatedMessage)
                }
            }
        }
    }

    TasksScreen(
        uiState = uiState,
        syncStatus = syncStatus,
        criteria = criteria,
        query = query,
        snackbarHostState = snackbarHostState,
        onRefresh = viewModel::refresh,
        onAddTaskClick = onAddTaskClick,
        onTaskClick = onTaskClick,
        onStartClick = viewModel::startTimer,
        onPauseClick = viewModel::pauseTimer,
        onDeleteClick = viewModel::deleteTask,
        onQueryChange = viewModel::onQueryChange,
        onSortFieldChange = viewModel::onSortFieldChange,
        onToggleSortDirection = viewModel::onToggleSortDirection,
        onToggleGrouping = viewModel::onToggleGrouping,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Stateless composable: renders a [TasksUiState] and reports user intent through callbacks only
 * (state hoisting), same as [com.neverlate.ui.articles.ArticlesScreen].
 *
 * Feature 11 adds the minimal sync UI (OQ-1): `PullToRefreshBox` (same widget
 * [com.neverlate.ui.articles.ArticlesScreen] already uses for its own refresh) wraps the content,
 * and [SyncStatusHint] shows a single subtle line only while [syncStatus] is worth mentioning
 * (syncing in the background, or offline) — nothing is shown once synced, keeping this
 * deliberately short of a rich per-task sync badge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    uiState: TasksUiState,
    syncStatus: SyncStatus,
    criteria: TaskListCriteria,
    query: String,
    onRefresh: () -> Unit,
    onAddTaskClick: () -> Unit,
    onTaskClick: (Long) -> Unit,
    onStartClick: (Long) -> Unit,
    onPauseClick: (Long) -> Unit,
    onDeleteClick: (Long) -> Unit,
    onQueryChange: (String) -> Unit,
    onSortFieldChange: (TaskSortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    onToggleGrouping: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tasks_title)) },
                navigationIcon = {
                    // Only rendered as a secondary screen: as a top-level bottom-bar tab
                    // (feature 18, the normal case), onBack is null and this slot stays empty.
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.tasks_back_content_description),
                            )
                        }
                    }
                },
                colors = brandedTopAppBarColors(),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddTaskClick,
                // Feature 20: the same fully-saturated brand pairing as the top app bars, so the
                // FAB reads as part of one coherent chrome instead of a stray default button.
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.tasks_add_content_description))
            }
        },
        // Task creation lands the user back on Tasks (feature 17's approved decision, now also
        // the app's landing tab per feature 18), so this is where the "task created" confirmation
        // (see TasksRoute) actually needs to render.
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = syncStatus == SyncStatus.Syncing,
            onRefresh = onRefresh,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SyncStatusHint(syncStatus)

                // Feature 03b: the search/sort/group controls only make sense once there is a
                // list to shape — hidden during Loading and Empty (no tasks exist yet at all),
                // shown for both NoResults (so the filter that caused it can be adjusted/cleared)
                // and Content.
                if (uiState is TasksUiState.NoResults || uiState is TasksUiState.Content) {
                    TaskListControls(
                        query = query,
                        criteria = criteria,
                        onQueryChange = onQueryChange,
                        onSortFieldChange = onSortFieldChange,
                        onToggleSortDirection = onToggleSortDirection,
                        onToggleGrouping = onToggleGrouping,
                    )
                }

                when (uiState) {
                    // Nothing to show yet: avoids a one-frame flash of the empty state while loading.
                    is TasksUiState.Loading -> Unit
                    // The action button starts task creation through the exact same destination
                    // the FAB above navigates to — MessageState only renders it because both
                    // actionLabel and onAction are supplied here.
                    is TasksUiState.Empty -> MessageState(
                        icon = Icons.AutoMirrored.Filled.Assignment,
                        message = stringResource(R.string.tasks_empty),
                        actionLabel = stringResource(R.string.tasks_empty_action),
                        onAction = onAddTaskClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Distinct from Empty above (US-4): there ARE tasks, just none matching the
                    // current filter. The action clears the filter rather than creating a task —
                    // "no coincidencias" is not "no data", so it gets a different fix.
                    is TasksUiState.NoResults -> MessageState(
                        icon = Icons.Filled.SearchOff,
                        message = stringResource(R.string.tasks_no_results),
                        actionLabel = stringResource(R.string.tasks_no_results_clear_filter),
                        onAction = { onQueryChange("") },
                        modifier = Modifier.fillMaxSize(),
                    )
                    is TasksUiState.Content -> ShapedTaskListView(
                        shaped = uiState.shaped,
                        onTaskClick = onTaskClick,
                        onStartClick = onStartClick,
                        onPauseClick = onPauseClick,
                        onDeleteClick = onDeleteClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * The "subtle hint" half of OQ-1's minimal sync UI: a single caption line, shown only for the two
 * statuses worth surfacing outside of the pull-to-refresh spinner itself — [SyncStatus.Offline]
 * (so a user who tried to sync understands why nothing happened) and [SyncStatus.Error]. Idle and
 * UpToDate render nothing, and Syncing is already covered by the spinner in [TasksScreen].
 */
@Composable
private fun SyncStatusHint(syncStatus: SyncStatus, modifier: Modifier = Modifier) {
    val textRes = when (syncStatus) {
        SyncStatus.Offline -> R.string.tasks_sync_offline
        SyncStatus.Error -> R.string.tasks_sync_error
        SyncStatus.Idle, SyncStatus.Syncing, SyncStatus.UpToDate -> null
    } ?: return

    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun TaskList(
    tasks: List<TaskUiModel>,
    onTaskClick: (Long) -> Unit,
    onStartClick: (Long) -> Unit,
    onPauseClick: (Long) -> Unit,
    onDeleteClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(tasks, key = { it.task.id }) { uiModel ->
            // Modifier.animateItem() (feature 17) animates this row's placement whenever the
            // list around it changes shape — insertion, removal, or reorder — for free, as long
            // as the item's `key` above stays stable across recompositions (it already is). A
            // deleted task's row animates out, and a newly created one animates in.
            TaskRow(
                uiModel = uiModel,
                onClick = { onTaskClick(uiModel.task.id) },
                onStartClick = { onStartClick(uiModel.task.id) },
                onPauseClick = { onPauseClick(uiModel.task.id) },
                onDeleteClick = { onDeleteClick(uiModel.task.id) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/**
 * Feature 03b's control row: a search field (US-1) plus sort/group [FilterChip]s and a direction
 * [IconButton] (US-2/US-3), all state-hoisted — this composable only reports intent through its
 * callbacks, the same stateless pattern the rest of this screen already follows.
 *
 * [FlowRow] (rather than a plain [Row]) is what lets the chips wrap onto a second line instead of
 * being clipped or pushing the list off-screen at the system's largest font scale (a visual AC).
 *
 * [query] is read separately from [criteria] (feature 04b): the field always shows the immediate,
 * un-debounced text (see [TasksViewModel]'s `query`), while [criteria] only ever holds sort/group.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskListControls(
    query: String,
    criteria: TaskListCriteria,
    onQueryChange: (String) -> Unit,
    onSortFieldChange: (TaskSortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    onToggleGrouping: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.tasks_filter_label)) },
            leadingIcon = {
                // Decorative: the label above already tells a screen reader what this field is for.
                Icon(Icons.Filled.Search, contentDescription = null)
            },
            trailingIcon = {
                // Feature 04b: only shown once there is text to clear, so an empty field shows no
                // trailing chrome at all. Clearing goes through the same onQueryChange callback the
                // field itself uses, exactly like NoResults' "clear filter" action below.
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.tasks_filter_clear_content_description),
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            FilterChip(
                selected = criteria.sortField == TaskSortField.Deadline,
                onClick = { onSortFieldChange(TaskSortField.Deadline) },
                label = { Text(stringResource(R.string.tasks_sort_deadline)) },
                modifier = Modifier.minimumInteractiveComponentSize(),
            )
            FilterChip(
                selected = criteria.sortField == TaskSortField.Title,
                onClick = { onSortFieldChange(TaskSortField.Title) },
                label = { Text(stringResource(R.string.tasks_sort_title)) },
                modifier = Modifier.minimumInteractiveComponentSize(),
            )

            // when as an exhaustive expression over SortDirection (TaskListShaping.kt), same
            // pattern as colorForUrgency below over UrgencyLevel: both the icon and the announced
            // state come from the one enum value, so they can never disagree with each other.
            val directionDescription = stringResource(
                when (criteria.direction) {
                    SortDirection.Ascending -> R.string.tasks_sort_direction_ascending
                    SortDirection.Descending -> R.string.tasks_sort_direction_descending
                },
            )
            IconButton(
                onClick = onToggleSortDirection,
                modifier = Modifier.semantics { stateDescription = directionDescription },
            ) {
                Icon(
                    imageVector = when (criteria.direction) {
                        SortDirection.Ascending -> Icons.Filled.ArrowUpward
                        SortDirection.Descending -> Icons.Filled.ArrowDownward
                    },
                    contentDescription = directionDescription,
                )
            }

            FilterChip(
                selected = criteria.grouped,
                onClick = onToggleGrouping,
                label = { Text(stringResource(R.string.tasks_group_by_urgency)) },
                modifier = Modifier.minimumInteractiveComponentSize(),
            )
        }
    }
}

/**
 * Renders a [ShapedTaskList] (feature 03b): [ShapedTaskList.Flat] as the plain [TaskList] already
 * used before this feature, [ShapedTaskList.Grouped] as one header + one sub-list per non-empty
 * urgency section, in the [Map]'s own iteration order (already fixed to
 * "Overdue → Urgent → Soon → Calm" by `shapedBy` in `TaskListShaping.kt`). Both branches reuse the
 * same [TaskRow] and `Modifier.animateItem()` (feature 17) — grouping only changes how rows are
 * split into sections, never how a single row itself renders or animates.
 */
@Composable
private fun ShapedTaskListView(
    shaped: ShapedTaskList,
    onTaskClick: (Long) -> Unit,
    onStartClick: (Long) -> Unit,
    onPauseClick: (Long) -> Unit,
    onDeleteClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (shaped) {
        is ShapedTaskList.Flat -> TaskList(
            tasks = shaped.tasks,
            onTaskClick = onTaskClick,
            onStartClick = onStartClick,
            onPauseClick = onPauseClick,
            onDeleteClick = onDeleteClick,
            modifier = modifier,
        )
        is ShapedTaskList.Grouped -> LazyColumn(modifier = modifier.fillMaxSize()) {
            // Destructuring a Map.Entry — the same component1()/component2() mechanism as
            // destructuring a Pair, here read as "level" and "tasksInSection".
            for ((level, tasksInSection) in shaped.sections) {
                item(key = level) { SectionHeader(level) }
                items(tasksInSection, key = { it.task.id }) { uiModel ->
                    TaskRow(
                        uiModel = uiModel,
                        onClick = { onTaskClick(uiModel.task.id) },
                        onStartClick = { onStartClick(uiModel.task.id) },
                        onPauseClick = { onPauseClick(uiModel.task.id) },
                        onDeleteClick = { onDeleteClick(uiModel.task.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/** A section header's label for [ShapedTaskList.Grouped], one exhaustive `when` per [UrgencyLevel]
 *  — the same defensive pattern [colorForUrgency] below already uses for the same enum. */
@Composable
private fun SectionHeader(level: UrgencyLevel, modifier: Modifier = Modifier) {
    val textRes = when (level) {
        UrgencyLevel.Overdue -> R.string.tasks_section_overdue
        UrgencyLevel.Urgent -> R.string.tasks_section_urgent
        UrgencyLevel.Soon -> R.string.tasks_section_soon
        UrgencyLevel.Calm -> R.string.tasks_section_calm
    }
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskRow(
    uiModel: TaskUiModel,
    onClick: () -> Unit,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Whether the delete confirmation dialog is showing is purely local UI state — it never
    // needs to survive beyond this composition, so it lives here via `remember` instead of in
    // TasksViewModel, same as showLogoutConfirm in SettingsScreen.
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val task = uiModel.task

    // Feature 17's urgency cue: urgencyLevelFor is a pure function of the same two values the
    // countdown Text below already reads, re-derived via derivedStateOf so that a *reader* of
    // urgencyLevel only recomposes when the derived UrgencyLevel itself actually changes (a
    // threshold crossing), not on every one-second tick that leaves the level unchanged — the
    // same "derive the cheap thing from the noisy thing" idea as MaterialTheme.colorScheme reads,
    // just computed here instead of supplied by the framework.
    val urgencyLevel by remember(uiModel.remainingMillis, uiModel.isTimedOut) {
        derivedStateOf { urgencyLevelFor(uiModel.remainingMillis, uiModel.isTimedOut) }
    }

    // Feature 19: a sibling derivedStateOf, keyed on the exact same inputs as urgencyLevel above,
    // so the bar's fraction and the countdown's color are two views of the one live value — never
    // two competing computations that could disagree. Null means "no meaningful total window" (see
    // deadlineProgressFor's KDoc), in which case no bar is rendered at all.
    val progress by remember(uiModel.remainingMillis, uiModel.isTimedOut) {
        derivedStateOf {
            deadlineProgressFor(uiModel.remainingMillis, task.estimatedDurationMillis, uiModel.isTimedOut)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        // Feature 20: the branded leading-icon chip sits alongside the row's existing content
        // (title, countdown, action buttons, progress bar) rather than inside it — a Row wraps
        // the chip and the untouched Column, which takes the remaining width (weight(1f)) so long
        // titles/countdowns still wrap instead of crowding the chip at large font scales.
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            BrandIconChip(icon = Icons.AutoMirrored.Filled.Assignment)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            ) {
                Text(text = task.title, style = MaterialTheme.typography.titleMedium)

                task.estimatedDurationMillis?.let { duration ->
                    Text(
                        text = stringResource(R.string.tasks_duration_label, durationLabel(duration)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                task.deadline?.let { deadline ->
                    // Read the current configuration's locale so the date follows the device
                    // language; locales[0] is the user's top preferred locale (available since
                    // API 24).
                    val locale = LocalConfiguration.current.locales[0]
                    Text(
                        text = stringResource(
                            R.string.tasks_deadline_label,
                            formatDeadlineForDisplay(deadline, locale),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (uiModel.isTimedOut) {
                            stringResource(R.string.tasks_time_up)
                        } else {
                            formatRemaining(uiModel.remainingMillis)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        // Urgency cue, now told twice (feature 19 adds the bar below to the color
                        // this text already had since feature 17). Overdue never relies on color
                        // alone: the "Tiempo agotado" / "Time's up" text above is still shown
                        // regardless of this color, so the state is legible even without color
                        // perception.
                        color = colorForUrgency(urgencyLevel),
                    )

                    Row {
                        if (!uiModel.isTimedOut) {
                            IconButton(onClick = if (task.isRunning) onPauseClick else onStartClick) {
                                Icon(
                                    imageVector = if (task.isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(
                                        if (task.isRunning) {
                                            R.string.tasks_pause_content_description
                                        } else {
                                            R.string.tasks_start_content_description
                                        },
                                    ),
                                )
                            }
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.tasks_delete_content_description),
                            )
                        }
                    }
                }

                // Feature 19: the deferred progress bar from feature 17's "v1: no progress bar"
                // note. `progress` is null exactly when there is no meaningful total window (see
                // deadlineProgressFor) — in that case we render nothing, rather than an arbitrary
                // fill.
                progress?.let { targetFraction ->
                    // Animate the *rendered* value while the *target* comes from derivedStateOf
                    // above: the target only changes when the fraction meaningfully moves, and
                    // animateFloatAsState eases the visible bar toward it instead of snapping,
                    // including the transition to a full bar on becoming overdue.
                    val animatedProgress by animateFloatAsState(
                        targetValue = targetFraction,
                        label = "deadlineProgress",
                    )
                    val locale = LocalConfiguration.current.locales[0]
                    val percentText = remember(locale) { NumberFormat.getPercentInstance(locale) }
                        .format(targetFraction)
                    val progressStateDescription =
                        stringResource(R.string.tasks_progress_state_description, percentText)
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        color = colorForUrgency(urgencyLevel),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            // The determinate indicator already exposes progressBarRangeInfo
                            // semantics for free (it is not decorative) — stateDescription adds a
                            // human-readable percentage on top, so a screen reader announces e.g.
                            // "45% transcurrido" instead of just a raw 0..1 ratio.
                            .semantics { stateDescription = progressStateDescription },
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        DeleteTaskDialog(
            taskTitle = task.title,
            onConfirm = {
                showDeleteConfirm = false
                onDeleteClick()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/**
 * Maps a pure [UrgencyLevel] to a themed [Color] (feature 17). [UrgencyLevel.Urgent] and
 * [UrgencyLevel.Overdue] both reuse [MaterialTheme.colorScheme]'s existing `error` role — visually
 * "urgent" and "error" are the same signal, "look at this now" — while [UrgencyLevel.Calm]/
 * [UrgencyLevel.Soon] read from [NeverLateExtras], the small extra color set feature 17 adds
 * alongside `MaterialTheme.colorScheme` for the roles Material 3 itself doesn't define (see
 * `Theme.kt`'s `NeverLateExtendedColors`).
 */
@Composable
private fun colorForUrgency(level: UrgencyLevel): Color = when (level) {
    UrgencyLevel.Calm -> NeverLateExtras.colors.calm
    UrgencyLevel.Soon -> NeverLateExtras.colors.soon
    UrgencyLevel.Urgent, UrgencyLevel.Overdue -> MaterialTheme.colorScheme.error
}

/**
 * Builds a localized estimated-duration label (e.g. "1 h 30 min" / "1h 30min") from an epoch-millis
 * duration. The numbers are formatted with a locale-aware [NumberFormat] and the units + word order
 * come from string resources, so nothing is concatenated in code — the whole point of feature 08.
 */
@Composable
private fun durationLabel(millis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val (hours, minutes) = durationParts(millis)
    return when {
        hours > 0 && minutes > 0 -> stringResource(
            R.string.tasks_duration_hours_minutes,
            numberFormat.format(hours),
            numberFormat.format(minutes),
        )
        hours > 0 -> stringResource(R.string.tasks_duration_hours, numberFormat.format(hours))
        else -> stringResource(R.string.tasks_duration_minutes, numberFormat.format(minutes))
    }
}

@Composable
private fun DeleteTaskDialog(taskTitle: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tasks_delete_confirm_title)) },
        text = { Text(stringResource(R.string.tasks_delete_confirm_message, taskTitle)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.tasks_delete_confirm_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.tasks_delete_cancel_button)) }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun TasksScreenContentPreview() {
    NeverLateTheme {
        TasksScreen(
            uiState = TasksUiState.Content(
                ShapedTaskList.Flat(
                    listOf(
                        TaskUiModel(
                            task = Task(id = 1, title = "Preparar la presentación", estimatedDurationMillis = 25 * 60_000L),
                            remainingMillis = 25 * 60_000L,
                            isTimedOut = false,
                        ),
                        TaskUiModel(
                            task = Task(id = 2, title = "Enviar el informe", timerEndsAt = Long.MAX_VALUE),
                            remainingMillis = 0L,
                            isTimedOut = true,
                        ),
                    ),
                ),
            ),
            syncStatus = SyncStatus.UpToDate,
            criteria = TaskListCriteria(),
            query = "",
            onRefresh = {},
            onAddTaskClick = {},
            onTaskClick = {},
            onStartClick = {},
            onPauseClick = {},
            onDeleteClick = {},
            onQueryChange = {},
            onSortFieldChange = {},
            onToggleSortDirection = {},
            onToggleGrouping = {},
            onBack = null,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TasksScreenEmptyPreview() {
    NeverLateTheme {
        TasksScreen(
            uiState = TasksUiState.Empty,
            syncStatus = SyncStatus.Idle,
            criteria = TaskListCriteria(),
            query = "",
            onRefresh = {},
            onAddTaskClick = {},
            onTaskClick = {},
            onStartClick = {},
            onPauseClick = {},
            onDeleteClick = {},
            onQueryChange = {},
            onSortFieldChange = {},
            onToggleSortDirection = {},
            onToggleGrouping = {},
            onBack = null,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TasksScreenNoResultsPreview() {
    NeverLateTheme {
        TasksScreen(
            uiState = TasksUiState.NoResults,
            syncStatus = SyncStatus.Idle,
            criteria = TaskListCriteria(),
            query = "xyz",
            onRefresh = {},
            onAddTaskClick = {},
            onTaskClick = {},
            onStartClick = {},
            onPauseClick = {},
            onDeleteClick = {},
            onQueryChange = {},
            onSortFieldChange = {},
            onToggleSortDirection = {},
            onToggleGrouping = {},
            onBack = null,
        )
    }
}
