package com.neverlate.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.auth.AuthErrorType
import com.neverlate.data.auth.AuthRepository
import com.neverlate.data.auth.AuthResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the Register screen needs to render itself. */
data class RegisterUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorType: AuthErrorType? = null,
)

/**
 * Backs the Register screen (US-1). Same "no explicit navigation on success" design as
 * [LoginViewModel] — see its KDoc.
 */
class RegisterViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    fun onEmailChange(email: String) {
        _uiState.update { it.copy(email = email, errorType = null) }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, errorType = null) }
    }

    fun register() {
        val state = _uiState.value
        if (state.isLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorType = null) }
            when (val result = repository.register(state.email.trim(), state.password)) {
                is AuthResult.Success -> _uiState.update { it.copy(isLoading = false) }
                is AuthResult.Error -> _uiState.update { it.copy(isLoading = false, errorType = result.type) }
            }
        }
    }
}
