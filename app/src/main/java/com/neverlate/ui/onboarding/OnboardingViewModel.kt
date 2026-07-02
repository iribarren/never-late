package com.neverlate.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the Onboarding screen needs to render itself.
 *
 * Bundling the field text and its derived validity into one immutable data class (instead of
 * two loose `remember`ed variables) is a common Compose pattern: the whole screen recomposes
 * from a single source of truth, which also makes it trivial to unit-test.
 */
data class OnboardingUiState(
    val name: String = "",
    val isSaveEnabled: Boolean = false,
)

/**
 * Holds the Onboarding screen's state so it survives configuration changes (e.g. rotation) —
 * something a plain `remember { mutableStateOf(...) } inside a composable would NOT do, since
 * that state is tied to the composable's lifecycle, not the screen's.
 *
 * State flows out to the UI via [uiState]; user intent flows back in through [onNameChange] and
 * [save] (unidirectional data flow).
 */
class OnboardingViewModel(private val repository: UserPreferencesRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** Called on every keystroke in the name field. */
    fun onNameChange(newName: String) {
        _uiState.update { current ->
            // Whitespace-only input counts as blank, so trimming isn't needed here for the
            // check — but the value saved to disk is trimmed in [save].
            current.copy(name = newName, isSaveEnabled = newName.isNotBlank())
        }
    }

    /**
     * Persists the trimmed name and marks the user as onboarded, then invokes [onSaved] so the
     * caller (the navigation layer) can move on to Home.
     *
     * Runs on [viewModelScope], a coroutine scope tied to this ViewModel's lifecycle: it is
     * cancelled automatically when the ViewModel is cleared, so there is no leaked work.
     */
    fun save(onSaved: () -> Unit) {
        val name = _uiState.value.name
        if (name.isBlank()) return // Defensive: the Save button should already be disabled.

        viewModelScope.launch {
            repository.saveOnboarding(name)
            onSaved()
        }
    }
}
