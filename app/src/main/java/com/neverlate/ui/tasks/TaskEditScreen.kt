package com.neverlate.ui.tasks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neverlate.R
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.data.tasks.TaskValidationError
import com.neverlate.ui.navigation.AppViewModelFactory
import com.neverlate.ui.theme.NeverLateTheme

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
                value = uiState.deadlineText,
                onValueChange = onDeadlineChange,
                label = { Text(stringResource(R.string.task_edit_deadline_label)) },
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
