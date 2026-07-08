package com.neverlate.ui.settings

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.neverlate.ui.components.brandedTopAppBarColors
import com.neverlate.ui.navigation.AppViewModelFactory
import com.neverlate.ui.notification.ReminderScheduler
import com.neverlate.ui.theme.NeverLateTheme

/**
 * Stateful wrapper: obtains [SettingsViewModel] (via [AppViewModelFactory]) and forwards its
 * state to the stateless [SettingsScreen], following the same route/screen split used across the
 * app (see [com.neverlate.ui.onboarding.OnboardingRoute]). [taskRepository]/[reminderScheduler]
 * are only needed by [SettingsViewModel]'s reminders on/off switch — see its KDoc.
 * [authRepository] (feature 11) backs the logout action. [onSignInClick] (feature 13) is plain
 * navigation — not a [SettingsViewModel] action, since it has no auth side effect of its own —
 * wired by [com.neverlate.ui.navigation.AppNavHost] to the Login destination it added inside
 * `MainAppNavHost`.
 *
 * [onBack] is `null` when Settings is reached as a top-level bottom-bar tab (feature 18) — there
 * is no back arrow to show in that case, since the bar itself is the way to leave this screen.
 */
@Composable
fun SettingsRoute(
    repository: UserPreferencesRepository,
    taskRepository: TaskRepository,
    reminderScheduler: ReminderScheduler,
    authRepository: AuthRepository,
    onBack: (() -> Unit)? = null,
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
        onDynamicColorChanged = viewModel::onDynamicColorChanged,
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
    onDynamicColorChanged: (Boolean) -> Unit,
    onLogoutClick: () -> Unit,
    onSignInClick: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    // Only rendered as a secondary screen: as a top-level bottom-bar tab
                    // (feature 18, the normal case), onBack is null and this slot stays empty.
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.settings_back_content_description),
                            )
                        }
                    }
                },
                colors = brandedTopAppBarColors(),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                // The content can outgrow the viewport once reminders are on (the lead-time radio
                // list + exact-alarm notice), which would otherwise push the Account section off
                // the bottom with no way to reach it — so make the whole screen scrollable.
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            // Spacing between the three cards themselves; the padding-only gaps that used to
            // separate section titles are now HorizontalDivider/spacing inside each card.
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSectionCard(
                title = stringResource(R.string.settings_theme_section),
                icon = Icons.Filled.Palette,
            ) {
                // selectableGroup() marks these rows as a single-choice set for accessibility, so
                // a screen reader announces them as "1 of 3" radio options instead of three
                // unrelated ones.
                Column(modifier = Modifier.selectableGroup()) {
                    themeOptions.forEach { option ->
                        SelectableRadioRow(
                            label = stringResource(option.labelRes),
                            selected = uiState.themeMode == option.mode,
                            onClick = { onThemeModeSelected(option.mode) },
                        )
                    }
                }

                // Material You / dynamic color (feature 16, US-3): only meaningful on Android 12+,
                // since dynamicColor has no effect below that (NeverLateTheme always renders the
                // brand scheme there) — showing an inert switch would just be a dead control, so it
                // is hidden entirely rather than shown disabled, per the approved decision.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_dynamic_color_label),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = uiState.dynamicColor, onCheckedChange = onDynamicColorChanged)
                    }
                }
            }

            SettingsSectionCard(
                title = stringResource(R.string.settings_reminders_section),
                icon = Icons.Filled.Notifications,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_reminders_enabled_label),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = uiState.remindersEnabled, onCheckedChange = onRemindersEnabledChanged)
                }

                // Only meaningful while reminders are on: with them off there is nothing to lead
                // up to. A divider anchors it as a distinct sub-block within the card.
                if (uiState.remindersEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Text(
                        text = stringResource(R.string.settings_reminder_lead_time_label),
                        style = MaterialTheme.typography.bodyMedium,
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
            }

            // Feature 11: account section. Feature 13: the action itself now depends on
            // authState — LoggedIn shows "Log out"; Guest shows "Sign in / Create account"
            // instead (this screen is never reached while LoggedOut). Feature 18 (US-4) guards
            // the destructive logout action behind a confirmation AlertDialog — the guest
            // "Sign in / Create account" entry has no destructive effect, so it stays a direct
            // TextButton.
            SettingsSectionCard(
                title = stringResource(R.string.settings_account_section),
                icon = Icons.Filled.AccountCircle,
            ) {
                if (uiState.authState is AuthState.LoggedIn) {
                    // Whether the confirmation dialog is showing is purely local UI state — it
                    // never needs to survive beyond this composition, so it lives here via
                    // `remember` instead of in SettingsViewModel, same as showDeleteConfirm in
                    // TasksScreen.
                    var showLogoutConfirm by remember { mutableStateOf(false) }

                    TextButton(onClick = { showLogoutConfirm = true }) {
                        Text(stringResource(R.string.settings_logout_button))
                    }

                    if (showLogoutConfirm) {
                        LogoutConfirmDialog(
                            onConfirm = {
                                showLogoutConfirm = false
                                onLogoutClick()
                            },
                            onDismiss = { showLogoutConfirm = false },
                        )
                    }
                } else {
                    TextButton(onClick = onSignInClick) {
                        Text(stringResource(R.string.settings_sign_in_button))
                    }
                }
            }
        }
    }
}

/**
 * Reusable Settings section wrapper (US-2): a Material 3 [Card] with a header row combining an
 * [icon] and [title], followed by [content]. The header icon is decorative
 * (`contentDescription = null`) because [title] is rendered right next to it and already conveys
 * the section's meaning to a screen reader — labelling the icon too would just repeat the
 * announcement.
 *
 * Existing blocks (theme radio group, reminders switch/lead-time/exact-alarm notice, account
 * button) are passed in as [content] verbatim; this composable only supplies the card, the
 * icon+title header, and the divider that separates them from the controls below.
 */
@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider(modifier = Modifier.padding(top = 12.dp, bottom = 16.dp))
            content()
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
 * Feature 18 (US-4): guards the destructive logout action (which, per feature 13, wipes local
 * tasks/outbox on sign-out) behind an explicit confirmation, mirroring [DeleteTaskDialog] in
 * `TasksScreen.kt` — the same "AlertDialog with a title/message/confirm/dismiss TextButton" shape
 * reused for a different destructive action.
 */
@Composable
private fun LogoutConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_logout_confirm_title)) },
        text = { Text(stringResource(R.string.settings_logout_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_logout_confirm_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_logout_cancel_button)) }
        },
    )
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
            onDynamicColorChanged = {},
            onLogoutClick = {},
            onSignInClick = {},
            onBack = null,
        )
    }
}
