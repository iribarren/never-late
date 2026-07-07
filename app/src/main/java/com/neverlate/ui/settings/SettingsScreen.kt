package com.neverlate.ui.settings

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neverlate.R
import com.neverlate.data.ThemeMode
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.auth.AuthRepository
import com.neverlate.data.auth.AuthState
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.ui.navigation.AppViewModelFactory
import com.neverlate.ui.notification.ReminderScheduler
import com.neverlate.ui.theme.NeverLateTheme

/**
 * Stateful wrapper: obtains [SettingsViewModel] (via [AppViewModelFactory]) and forwards its
 * state to the stateless [SettingsScreen], following the same route/screen split used across the
 * app (see [com.neverlate.ui.home.HomeRoute]). [taskRepository]/[reminderScheduler] are only
 * needed by [SettingsViewModel]'s reminders on/off switch — see its KDoc. [authRepository]
 * (feature 11) backs the logout action. [onSignInClick] (feature 13) is plain navigation — not a
 * [SettingsViewModel] action, since it has no auth side effect of its own, exactly like
 * [onBack] — wired by [com.neverlate.ui.navigation.AppNavHost] to the Login destination it added
 * inside `MainAppNavHost`.
 */
@Composable
fun SettingsRoute(
    repository: UserPreferencesRepository,
    taskRepository: TaskRepository,
    reminderScheduler: ReminderScheduler,
    authRepository: AuthRepository,
    onBack: () -> Unit,
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(
        factory = AppViewModelFactory(
            userPreferencesRepository = repository,
            taskRepository = taskRepository,
            reminderScheduler = reminderScheduler,
            authRepository = authRepository,
        ),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState,
        onThemeModeSelected = viewModel::onThemeModeSelected,
        onRemindersEnabledChanged = viewModel::onRemindersEnabledChanged,
        onReminderLeadMinutesSelected = viewModel::onReminderLeadMinutesSelected,
        onLogoutClick = viewModel::logout,
        onSignInClick = onSignInClick,
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
 * Fixed set of lead-time choices (feature 09, US-4/OQ-4: one global lead time, no free-form
 * input). A small fixed list keeps the UI as simple radio rows, exactly like [themeOptions] above.
 */
private val reminderLeadMinuteOptions = listOf(5, 10, 15, 30, 60)

/**
 * Stateless composable: renders a [SettingsUiState] and reports the user's choices through
 * [onThemeModeSelected]/[onRemindersEnabledChanged]/[onReminderLeadMinutesSelected] only (state
 * hoisting), same as the other screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onRemindersEnabledChanged: (Boolean) -> Unit,
    onReminderLeadMinutesSelected: (Int) -> Unit,
    onLogoutClick: () -> Unit,
    onSignInClick: () -> Unit,
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
                    SelectableRadioRow(
                        label = stringResource(option.labelRes),
                        selected = uiState.themeMode == option.mode,
                        onClick = { onThemeModeSelected(option.mode) },
                    )
                }
            }

            Text(
                text = stringResource(R.string.settings_reminders_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_reminders_enabled_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = uiState.remindersEnabled, onCheckedChange = onRemindersEnabledChanged)
            }

            // Only meaningful while reminders are on: with them off there is nothing to lead up to.
            if (uiState.remindersEnabled) {
                Text(
                    text = stringResource(R.string.settings_reminder_lead_time_label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Column(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .selectableGroup(),
                ) {
                    reminderLeadMinuteOptions.forEach { minutes ->
                        SelectableRadioRow(
                            label = pluralStringResource(R.plurals.settings_reminder_lead_minutes, minutes, minutes),
                            selected = uiState.reminderLeadMinutes == minutes,
                            onClick = { onReminderLeadMinutesSelected(minutes) },
                        )
                    }
                }

                ExactAlarmPermissionNotice(modifier = Modifier.padding(top = 16.dp))
            }

            // Feature 11: account section. A plain TextButton (rather than a dialog-guarded
            // destructive action) matches this screen's existing minimal-UI style — see OQ-1's
            // "minimal sync UI" call in the feature spec for the same restraint. Feature 13: the
            // action itself now depends on authState — LoggedIn shows "Log out"; Guest shows
            // "Sign in / Create account" instead (this screen is never reached while LoggedOut).
            Text(
                text = stringResource(R.string.settings_account_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
            if (uiState.authState is AuthState.LoggedIn) {
                TextButton(onClick = onLogoutClick, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.settings_logout_button))
                }
            } else {
                TextButton(onClick = onSignInClick, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.settings_sign_in_button))
                }
            }
        }
    }
}

/**
 * One selectable choice as a full-width row — shared by the theme options above and the reminder
 * lead-time options below, since both are "pick exactly one from a small fixed list" (a single
 * `selectableGroup`). The whole row is `selectable` (not just the radio dot), so tapping anywhere
 * on it selects that option; `Role.RadioButton` tells accessibility services how to describe it.
 */
@Composable
private fun SelectableRadioRow(
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

/**
 * US-5 (approved, minimal scope): on API 31+, if the exact-alarm permission is not currently
 * granted, explains the trade-off and offers a shortcut to the system screen that grants it
 * (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`). On API < 31, or once the permission is already granted,
 * this renders nothing.
 *
 * The check runs once per composition (there is no callback for "the user came back from system
 * settings and changed this"), so leaving and reopening this screen re-evaluates it — good enough
 * for this minimal, informational notice.
 */
@Composable
private fun ExactAlarmPermissionNotice(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

    val context = LocalContext.current
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    if (alarmManager == null || alarmManager.canScheduleExactAlarms()) return

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.settings_exact_alarm_notice),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
            },
        ) {
            Text(stringResource(R.string.settings_exact_alarm_action))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    NeverLateTheme {
        SettingsScreen(
            uiState = SettingsUiState(themeMode = ThemeMode.SYSTEM),
            onThemeModeSelected = {},
            onRemindersEnabledChanged = {},
            onReminderLeadMinutesSelected = {},
            onLogoutClick = {},
            onSignInClick = {},
            onBack = {},
        )
    }
}
