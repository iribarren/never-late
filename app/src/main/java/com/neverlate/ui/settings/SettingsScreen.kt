package com.neverlate.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neverlate.R
import com.neverlate.data.ThemeMode
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.ui.navigation.AppViewModelFactory
import com.neverlate.ui.theme.NeverLateTheme

/**
 * Stateful wrapper: obtains [SettingsViewModel] (via [AppViewModelFactory]) and forwards its
 * state to the stateless [SettingsScreen], following the same route/screen split used across the
 * app (see [com.neverlate.ui.home.HomeRoute]).
 */
@Composable
fun SettingsRoute(
    repository: UserPreferencesRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelFactory(repository)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState,
        onThemeModeSelected = viewModel::onThemeModeSelected,
        onBack = onBack,
        modifier = modifier,
    )
}

/** One selectable theme option: the [ThemeMode] it stands for and the label to show for it. */
private data class ThemeOption(val mode: ThemeMode, val labelRes: Int)

private val themeOptions = listOf(
    ThemeOption(ThemeMode.LIGHT, R.string.settings_theme_light),
    ThemeOption(ThemeMode.DARK, R.string.settings_theme_dark),
    ThemeOption(ThemeMode.SYSTEM, R.string.settings_theme_system),
)

/**
 * Stateless composable: renders a [SettingsUiState] and reports the user's choice through the
 * [onThemeModeSelected] callback only (state hoisting), same as the other screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back_content_description),
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
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_theme_section),
                style = MaterialTheme.typography.titleMedium,
            )

            // selectableGroup() marks these rows as a single-choice set for accessibility, so a
            // screen reader announces them as "1 of 3" radio options instead of three unrelated ones.
            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .selectableGroup(),
            ) {
                themeOptions.forEach { option ->
                    ThemeOptionRow(
                        label = stringResource(option.labelRes),
                        selected = uiState.themeMode == option.mode,
                        onClick = { onThemeModeSelected(option.mode) },
                    )
                }
            }
        }
    }
}

/**
 * One theme choice as a full-width row. The whole row is `selectable` (not just the radio dot),
 * so tapping anywhere on it selects that option; `Role.RadioButton` tells accessibility services
 * how to describe it.
 */
@Composable
private fun ThemeOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // onClick = null: the row's `selectable` already handles the click, so the button itself
        // must not consume it a second time (Material 3 guidance for row-level selection).
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    NeverLateTheme {
        SettingsScreen(
            uiState = SettingsUiState(themeMode = ThemeMode.SYSTEM),
            onThemeModeSelected = {},
            onBack = {},
        )
    }
}
