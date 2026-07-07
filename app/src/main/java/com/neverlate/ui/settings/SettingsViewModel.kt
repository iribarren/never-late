package com.neverlate.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.ThemeMode
import com.neverlate.data.UserPreferences
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.auth.AuthRepository
import com.neverlate.data.auth.AuthState
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.ui.notification.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the Settings screen needs to render itself. [authState] (feature 13) drives the
 * account section: [AuthState.LoggedIn] shows "Log out"; [AuthState.Guest] shows "Sign in /
 * Create account" instead (this screen is only ever reached while one of those two holds — see
 * [com.neverlate.ui.navigation.AppNavHost] — so [AuthState.LoggedOut] is not rendered here).
 */
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val remindersEnabled: Boolean = true,
    val reminderLeadMinutes: Int = UserPreferences.DEFAULT_REMINDER_LEAD_MINUTES,
    val authState: AuthState = AuthState.Guest,
)

/**
 * Backs the Settings screen: exposes the currently selected [ThemeMode] plus the reminder
 * preferences (feature 09, US-4) and writes the user's choices back to [repository].
 *
 * Like [com.neverlate.ui.home.HomeViewModel], it continuously *observes* the repository's
 * preferences [Flow][kotlinx.coroutines.flow.Flow] so the UI stays in sync with what is actually
 * persisted — including the write this same screen just made.
 *
 * [taskRepository] and [reminderScheduler] are only needed for one thing: turning reminders
 * **off** must also cancel every already-scheduled alarm right away (US-4's "los pendientes se
 * cancelan"), not just stop new ones from being scheduled on the next edit. That cancel-all logic
 * does not belong in [com.neverlate.ui.notification.ReminderSchedulingRepository] — that decorator
 * only reacts to a *task* being saved or deleted, never to a *preference* changing — so it lives
 * here instead, the one place that already knows the preference just flipped.
 *
 * [authRepository] (feature 11) backs the account section: [logout] (shown while `LoggedIn`) and,
 * since feature 13, exposing `authState` itself so the screen can also show a "Sign in / Create
 * account" entry while `Guest`. Like [com.neverlate.ui.auth.LoginViewModel], this ViewModel does
 * not navigate anywhere itself on logout: [com.neverlate.ui.navigation.AppNavHost] reacts to
 * [AuthRepository]'s own `authState` flipping and swaps graphs on its own (US-2). Signing *in* from
 * here, on the other hand, is plain navigation to the Login/Register destinations added inside
 * `MainAppNavHost` — see [com.neverlate.ui.settings.SettingsScreen]'s `onSignInClick`.
 */
class SettingsViewModel(
    private val repository: UserPreferencesRepository,
    private val taskRepository: TaskRepository,
    private val reminderScheduler: ReminderScheduler,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // Two independent sources feed this one uiState, so each collector must merge into the
        // existing value (copy) rather than replace it wholesale — otherwise whichever flow last
        // emitted would silently wipe out the other's contribution.
        viewModelScope.launch {
            repository.userPreferences.collect { preferences ->
                _uiState.update {
                    it.copy(
                        themeMode = preferences.themeMode,
                        remindersEnabled = preferences.remindersEnabled,
                        reminderLeadMinutes = preferences.reminderLeadMinutes,
                    )
                }
            }
        }
        viewModelScope.launch {
            authRepository.authState.collect { authState ->
                _uiState.update { it.copy(authState = authState) }
            }
        }
    }

    /** Persists the newly chosen theme. The UI updates reactively once the write is observed. */
    fun onThemeModeSelected(mode: ThemeMode) {
        viewModelScope.launch {
            repository.saveThemeMode(mode)
        }
    }

    /**
     * Persists the reminders on/off switch. Turning it **off** also cancels every task's
     * already-scheduled reminder, read fresh from [taskRepository] and cancelled one by one via
     * [reminderScheduler] — the same per-task identity
     * [com.neverlate.ui.notification.ReminderSchedulingRepository] uses when it cancels a single
     * task's alarm.
     */
    fun onRemindersEnabledChanged(enabled: Boolean) {
        viewModelScope.launch {
            repository.saveRemindersEnabled(enabled)
            if (!enabled) {
                taskRepository.observeTasks().first().forEach { task -> reminderScheduler.cancel(task.id) }
            }
        }
    }

    /**
     * Persists the default lead time. OQ-3 (approved): this only affects reminders scheduled from
     * now on; reminders already scheduled under the previous lead time are left as they are.
     */
    fun onReminderLeadMinutesSelected(minutes: Int) {
        viewModelScope.launch {
            repository.saveReminderLeadMinutes(minutes)
        }
    }

    /**
     * Clears the session and the locally-cached, backend-owned data (US-2), landing back in
     * [AuthState.Guest] (feature 13) rather than the login gate — see [AuthRepository.logout]'s
     * KDoc for exactly what that wipes and why.
     */
    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }
}
