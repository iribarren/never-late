package com.neverlate.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neverlate.R
import com.neverlate.data.sync.SyncStatus
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.data.tasks.durationParts
import com.neverlate.data.tasks.formatDeadlineForDisplay
import com.neverlate.data.tasks.formatRemaining
import com.neverlate.ui.navigation.AppViewModelFactory
import com.neverlate.ui.notification.RequestNotificationPermissionEffect
import com.neverlate.ui.theme.NeverLateTheme
import java.text.NumberFormat

/**
 * Stateful wrapper: obtains [TasksViewModel] (via [AppViewModelFactory]) and forwards its state
 * to the stateless [TasksScreen], following the same route/screen split used for Articles (see
 * [com.neverlate.ui.articles.ArticlesRoute]).
 */
@Composable
fun TasksRoute(
    taskRepository: TaskRepository,
    onAddTaskClick: () -> Unit,
    onTaskClick: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TasksViewModel = viewModel(factory = AppViewModelFactory(taskRepository = taskRepository)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    // Ask for the POST_NOTIFICATIONS permission (Android 13+) in context, the first time the user
    // reaches their tasks — that is when "also show these on the lock screen" is meaningful. No-op
    // below Android 13, and denial degrades gracefully (see the effect's KDoc / feature 06).
    RequestNotificationPermissionEffect()
    TasksScreen(
        uiState = uiState,
        syncStatus = syncStatus,
        onRefresh = viewModel::refresh,
        onAddTaskClick = onAddTaskClick,
        onTaskClick = onTaskClick,
        onStartClick = viewModel::startTimer,
        onPauseClick = viewModel::pauseTimer,
        onDeleteClick = viewModel::deleteTask,
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
    onRefresh: () -> Unit,
    onAddTaskClick: () -> Unit,
    onTaskClick: (Long) -> Unit,
    onStartClick: (Long) -> Unit,
    onPauseClick: (Long) -> Unit,
    onDeleteClick: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tasks_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.tasks_back_content_description),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddTaskClick) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.tasks_add_content_description))
            }
        },
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

                when (uiState) {
                    // Nothing to show yet: avoids a one-frame flash of the empty state while loading.
                    is TasksUiState.Loading -> Unit
                    is TasksUiState.Empty -> EmptyTasks(modifier = Modifier.fillMaxSize())
                    is TasksUiState.Content -> TaskList(
                        tasks = uiState.tasks,
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
            TaskRow(
                uiModel = uiModel,
                onClick = { onTaskClick(uiModel.task.id) },
                onStartClick = { onStartClick(uiModel.task.id) },
                onPauseClick = { onPauseClick(uiModel.task.id) },
                onDeleteClick = { onDeleteClick(uiModel.task.id) },
            )
        }
    }
}

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
    // TasksViewModel, same as the Snackbar state in com.neverlate.ui.home.HomeScreen.
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val task = uiModel.task

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = task.title, style = MaterialTheme.typography.titleMedium)

            task.estimatedDurationMillis?.let { duration ->
                Text(
                    text = stringResource(R.string.tasks_duration_label, durationLabel(duration)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            task.deadline?.let { deadline ->
                // Read the current configuration's locale so the date follows the device language;
                // locales[0] is the user's top preferred locale (available since API 24).
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

@Composable
private fun EmptyTasks(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.tasks_empty))
    }
}

@Preview(showBackground = true)
@Composable
private fun TasksScreenContentPreview() {
    NeverLateTheme {
        TasksScreen(
            uiState = TasksUiState.Content(
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
            syncStatus = SyncStatus.UpToDate,
            onRefresh = {},
            onAddTaskClick = {},
            onTaskClick = {},
            onStartClick = {},
            onPauseClick = {},
            onDeleteClick = {},
            onBack = {},
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
            onRefresh = {},
            onAddTaskClick = {},
            onTaskClick = {},
            onStartClick = {},
            onPauseClick = {},
            onDeleteClick = {},
            onBack = {},
        )
    }
}
