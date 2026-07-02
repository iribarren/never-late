package com.neverlate.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neverlate.R
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.ui.navigation.AppViewModelFactory
import com.neverlate.ui.theme.NeverLateTheme

/**
 * Stateful wrapper (the "route"): this is the only place that knows an [OnboardingViewModel]
 * exists. It obtains the ViewModel (built by [AppViewModelFactory], since it needs a
 * [repository]), collects its state, and wires callbacks into the stateless [OnboardingScreen].
 *
 * `collectAsStateWithLifecycle` (instead of the plain `collectAsState`) pauses collection while
 * the screen is not visible (e.g. app backgrounded), which avoids doing work for nothing.
 */
@Composable
fun OnboardingRoute(
    repository: UserPreferencesRepository,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = viewModel(factory = AppViewModelFactory(repository)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    OnboardingScreen(
        uiState = uiState,
        onNameChange = viewModel::onNameChange,
        onSave = { viewModel.save(onSaved) },
        modifier = modifier,
    )
}

/**
 * Stateless composable: it only knows how to render an [OnboardingUiState] and report user
 * intent through callbacks. This is "state hoisting" — the state lives above this function
 * (in the ViewModel) and is merely passed in, which makes this composable trivial to preview
 * and to test in isolation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    uiState: OnboardingUiState,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_prompt),
                style = MaterialTheme.typography.titleMedium,
            )

            // A single mutable field: value + onValueChange is the standard Compose text-input
            // pattern. Here the "state" behind `value` lives in the ViewModel, not in `remember`.
            OutlinedTextField(
                value = uiState.name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.onboarding_name_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )

            Button(
                onClick = onSave,
                enabled = uiState.isSaveEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            ) {
                Text(stringResource(R.string.onboarding_save_button))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenEmptyPreview() {
    NeverLateTheme {
        OnboardingScreen(uiState = OnboardingUiState(), onNameChange = {}, onSave = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenFilledPreview() {
    NeverLateTheme {
        OnboardingScreen(
            uiState = OnboardingUiState(name = "Ada", isSaveEnabled = true),
            onNameChange = {},
            onSave = {},
        )
    }
}
