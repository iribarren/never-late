package com.neverlate.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.auth.AuthErrorType
import com.neverlate.data.auth.AuthRepository
import com.neverlate.data.auth.AuthResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the Login screen needs to render itself. */
data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorType: AuthErrorType? = null,
)

/**
 * Backs the Login screen (US-1). On success, [repository]'s own `authState` flips to
 * [com.neverlate.data.auth.AuthState.LoggedIn]; this ViewModel does not navigate anywhere itself —
 * [com.neverlate.ui.navigation.AppNavHost] observes that state directly and reactively swaps the
 * whole auth-gated graph for the main one once it changes, the same way it already reacts to the
 * `onboarded` preference.
 *
 * Feature 13d: `@HiltViewModel` + `@Inject constructor`, obtained via `hiltViewModel()`.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(private val repository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onEmailChange(email: String) {
        _uiState.update { it.copy(email = email, errorType = null) }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, errorType = null) }
    }

    fun login() {
        val state = _uiState.value
        if (state.isLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorType = null) }
            when (val result = repository.login(state.email.trim(), state.password)) {
                is AuthResult.Success -> _uiState.update { it.copy(isLoading = false) }
                is AuthResult.Error -> _uiState.update { it.copy(isLoading = false, errorType = result.type) }
            }
        }
    }
}
