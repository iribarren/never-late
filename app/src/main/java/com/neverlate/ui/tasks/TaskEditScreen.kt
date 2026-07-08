package com.neverlate.ui.tasks

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.text.format.DateFormat
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neverlate.R
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.data.tasks.TaskValidationError
import com.neverlate.data.tasks.deadlineFromPickedDateTime
import com.neverlate.data.tasks.formatDeadlineForDisplay
import com.neverlate.data.tasks.formatDeadlineForInput
import com.neverlate.data.tasks.parseDeadline
import com.neverlate.ui.navigation.AppViewModelFactory
import com.neverlate.ui.theme.NeverLateTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.collectLatest

/**
 * Stateful wrapper: obtains [TaskEditViewModel] (via [AppViewModelFactory], passing the
 * nullable [taskId] that came from the navigation route) and forwards its state to the stateless
 * [TaskEditScreen].
 */
@Composable
fun TaskEditRoute(
    taskRepository: TaskRepository,
    taskId: Long?,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TaskEditViewModel = viewModel(
        factory = AppViewModelFactory(taskRepository = taskRepository, taskId = taskId),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Navigating back is a one-shot reaction to isSaved flipping to true, not something the
    // Screen itself decides — LaunchedEffect re-runs only when its key (isSaved) actually
    // changes, so this fires exactly once per successful save/delete instead of on every
    // recomposition.
    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) onSaved()
    }

    TaskEditScreen(
        uiState = uiState,
        isEditing = taskId != null,
        onTitleChange = viewModel::onTitleChange,
        onDurationChange = viewModel::onDurationMinutesChange,
        onDeadlineChange = viewModel::onDeadlineTextChange,
        onSaveClick = viewModel::save,
        onDeleteClick = viewModel::deleteTask,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Stateless composable: renders a [TaskEditUiState] and reports user intent through callbacks
 * only (state hoisting), same as [com.neverlate.ui.articles.ArticleDetailScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditScreen(
    uiState: TaskEditUiState,
    isEditing: Boolean,
    onTitleChange: (String) -> Unit,
    onDurationChange: (String) -> Unit,
    onDeadlineChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The canonical `deadlineText` (dd/MM/yyyy HH:mm, see TaskTiming.kt) parsed back to epoch
    // millis, recomputed whenever it changes. Null means "no deadline set" (or, in principle, an
    // unparseable value — which the picker itself can no longer produce).
    val parsedDeadlineMillis = remember(uiState.deadlineText) { parseDeadline(uiState.deadlineText) }

    // The field never shows the raw machine-readable `deadlineText` pattern anymore — only a
    // locale-aware rendering (feature 08's formatDeadlineForDisplay), following the same
    // LocalConfiguration.current.locales[0] pattern as TaskRow.
    val locale = LocalConfiguration.current.locales[0]
    val deadlineDisplayText = parsedDeadlineMillis?.let { formatDeadlineForDisplay(it, locale) } ?: ""

    // Dialog open/closed flags are ephemeral UI state: they don't need to survive process death
    // and aren't part of the form data the ViewModel owns, so they live here via `remember`
    // rather than in TaskEditUiState.
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    // Bridges the date dialog's answer over to the time dialog: the UTC-midnight millis the user
    // just confirmed in the DatePicker, held until the time is also known (see the confirm
    // handler below for why this stays in UTC until the very last step).
    var pickedDateUtcMillis by remember { mutableStateOf<Long?>(null) }

    // A `readOnly` OutlinedTextField does not react to a normal click the way an editable one
    // does, so we watch its interaction source directly: a press-release on the field opens the
    // same flow as tapping the calendar icon.
    val deadlineFieldInteractionSource = remember { MutableInteractionSource() }
    LaunchedEffect(deadlineFieldInteractionSource) {
        deadlineFieldInteractionSource.interactions.collectLatest { interaction ->
            if (interaction is PressInteraction.Release) {
                showDatePicker = true
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isEditing) R.string.task_edit_title_edit else R.string.task_edit_title_create,
                        ),
                    )
                },
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = uiState.title,
                onValueChange = onTitleChange,
                label = { Text(stringResource(R.string.task_edit_title_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.estimatedDurationMinutes,
                onValueChange = onDurationChange,
                label = { Text(stringResource(R.string.task_edit_duration_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            OutlinedTextField(
                value = deadlineDisplayText,
                onValueChange = {}, // readOnly: the user picks a deadline, never types it
                readOnly = true,
                interactionSource = deadlineFieldInteractionSource,
                label = { Text(stringResource(R.string.task_edit_deadline_label)) },
                trailingIcon = {
                    Row {
                        if (uiState.deadlineText.isNotBlank()) {
                            IconButton(onClick = { onDeadlineChange("") }) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = stringResource(
                                        R.string.task_edit_deadline_clear_content_description,
                                    ),
                                )
                            }
                        }
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(
                                imageVector = Icons.Filled.CalendarMonth,
                                contentDescription = stringResource(
                                    R.string.task_edit_deadline_pick_content_description,
                                ),
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            uiState.validationError?.let { error ->
                Text(
                    text = stringResource(error.toStringRes()),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Button(onClick = onSaveClick, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.task_edit_save_button))
            }

            if (isEditing) {
                OutlinedButton(onClick = onDeleteClick, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.task_edit_delete_button))
                }
            }
        }
    }

    if (showDatePicker) {
        // Seed the picker with the *current* deadline's calendar date, re-expressed as UTC
        // midnight — the exact inverse of the UTC→local conversion performed on final confirm
        // below (DatePickerState always works in UTC, regardless of device zone). With no
        // current deadline, default to today.
        val initialUtcMillis = remember(parsedDeadlineMillis) {
            val localDate = parsedDeadlineMillis
                ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
                ?: LocalDate.now()
            localDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMillis)

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickedDateUtcMillis = datePickerState.selectedDateMillis
                    showDatePicker = false
                    showTimePicker = true
                }) {
                    Text(stringResource(R.string.task_edit_date_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.task_edit_date_dismiss))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val context = LocalContext.current
        // Seed the time picker from the current deadline's local time, or the current clock time
        // for a brand-new deadline — a sensible default per US-3.
        val initialTime = remember(parsedDeadlineMillis) {
            parsedDeadlineMillis
                ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime() }
                ?: LocalTime.now()
        }
        val timePickerState = rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
            is24Hour = DateFormat.is24HourFormat(context),
        )

        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val selectedUtcMillis = pickedDateUtcMillis
                    if (selectedUtcMillis != null) {
                        // The UTC-midnight → local-zone conversion (the feature's key correctness
                        // point, US-4) lives as a pure, unit-tested function in TaskTiming.kt, so the
                        // "off-by-one-day" logic is covered without instrumenting the UI.
                        val epochMillis = deadlineFromPickedDateTime(
                            dateUtcMidnightMillis = selectedUtcMillis,
                            hour = timePickerState.hour,
                            minute = timePickerState.minute,
                        )
                        onDeadlineChange(formatDeadlineForInput(epochMillis))
                    }
                    showTimePicker = false
                }) {
                    Text(stringResource(R.string.task_edit_date_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.task_edit_date_dismiss))
                }
            },
            text = { TimePicker(state = timePickerState) },
        )
    }
}

/** Maps each validation problem to its user-facing message, kept in `strings.xml` per project convention. */
private fun TaskValidationError.toStringRes(): Int = when (this) {
    TaskValidationError.BLANK_TITLE -> R.string.tasks_error_blank_title
    TaskValidationError.INVALID_DURATION -> R.string.tasks_error_invalid_duration
    TaskValidationError.INVALID_DEADLINE_FORMAT -> R.string.tasks_error_invalid_deadline_format
    TaskValidationError.MISSING_DURATION_OR_DEADLINE -> R.string.tasks_error_missing_duration_or_deadline
}

@Preview(showBackground = true)
@Composable
private fun TaskEditScreenCreatePreview() {
    NeverLateTheme {
        TaskEditScreen(
            uiState = TaskEditUiState(),
            isEditing = false,
            onTitleChange = {},
            onDurationChange = {},
            onDeadlineChange = {},
            onSaveClick = {},
            onDeleteClick = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TaskEditScreenEditPreview() {
    NeverLateTheme {
        TaskEditScreen(
            uiState = TaskEditUiState(
                title = "Preparar la presentación",
                estimatedDurationMinutes = "25",
                deadlineText = "24/12/2026 20:30",
            ),
            isEditing = true,
            onTitleChange = {},
            onDurationChange = {},
            onDeadlineChange = {},
            onSaveClick = {},
            onDeleteClick = {},
            onBack = {},
        )
    }
}
