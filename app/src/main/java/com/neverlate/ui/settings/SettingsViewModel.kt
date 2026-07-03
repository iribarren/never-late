package com.neverlate.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.ThemeMode
import com.neverlate.data.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Everything the Settings screen needs to render itself. */
data class SettingsUiState(val themeMode: ThemeMode = ThemeMode.SYSTEM)

/**
 * Backs the Settings screen: exposes the currently selected [ThemeMode] and writes the user's
 * new choice back to the repository.
 *
 * Like [com.neverlate.ui.home.HomeViewModel], it continuously *observes* the repository's
 * preferences [Flow][kotlinx.coroutines.flow.Flow] so the selected radio button stays in sync
 * with what is actually persisted — including the write this same screen just made.
 */
class SettingsViewModel(private val repository: UserPreferencesRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.userPreferences.collect { preferences ->
                _uiState.value = SettingsUiState(themeMode = preferences.themeMode)
            }
        }
    }

    /** Persists the newly chosen theme. The UI updates reactively once the write is observed. */
    fun onThemeModeSelected(mode: ThemeMode) {
        viewModelScope.launch {
            repository.saveThemeMode(mode)
        }
    }
}
