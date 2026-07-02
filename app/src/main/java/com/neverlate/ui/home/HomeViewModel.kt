package com.neverlate.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Everything the Home screen needs to render itself. */
data class HomeUiState(val name: String = "")

/**
 * Exposes the persisted user name to Home.
 *
 * Unlike [com.neverlate.ui.onboarding.OnboardingViewModel] (which only writes once), this
 * ViewModel continuously *observes* the repository's [Flow][kotlinx.coroutines.flow.Flow] of
 * [com.neverlate.data.UserPreferences], re-collecting it into its own [StateFlow] for the UI.
 */
class HomeViewModel(repository: UserPreferencesRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.userPreferences.collect { preferences ->
                _uiState.value = HomeUiState(name = preferences.name)
            }
        }
    }
}
