package com.neverlate.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neverlate.R
import com.neverlate.data.auth.AuthErrorType
import com.neverlate.data.auth.AuthRepository
import com.neverlate.ui.components.brandedTopAppBarColors
import com.neverlate.ui.theme.NeverLateTheme

/**
 * Stateful wrapper: obtains [LoginViewModel] via `hiltViewModel()` (feature 13d) and forwards its
 * state to the stateless [LoginScreen], following the same route/screen split used across the app
 * (see [com.neverlate.ui.onboarding.OnboardingRoute]).
 *
 * There is no `onLoggedIn` callback: [com.neverlate.ui.navigation.AppNavHost] reacts to
 * [AuthRepository]'s own `authState` directly (see [LoginViewModel]'s KDoc), so a successful login
 * here needs no explicit navigation call at all.
 *
 * [onBack] (feature 13) is `null` when this screen is the mandatory
 * [com.neverlate.data.auth.AuthState.LoggedOut] gate's root destination (`AuthGateNavHost`) —
 * there is nothing to go back *to* there, so no back arrow is shown. It is non-null when reached
 * from Settings while [com.neverlate.data.auth.AuthState.Guest] (`MainAppNavHost`), so a guest who
 * opens the login screen and changes their mind can return to the app instead of being stuck
 * on it.
 */
@Composable
fun LoginRoute(
    onRegisterClick: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LoginScreen(
        uiState = uiState,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onLoginClick = viewModel::login,
        onRegisterClick = onRegisterClick,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Stateless composable: renders a [LoginUiState] and reports user intent through callbacks only
 * (state hoisting), same as every other screen in this app. [onBack] is optional — see
 * [LoginRoute]'s KDoc for when it is/isn't passed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    uiState: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLoginClick: () -> Unit,
    onRegisterClick: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.login_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.login_back_content_description),
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
                .padding(24.dp),
        ) {
            OutlinedTextField(
                value = uiState.email,
                onValueChange = onEmailChange,
                label = { Text(stringResource(R.string.auth_email_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.auth_password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            uiState.errorType?.let { errorType ->
                Text(
                    text = stringResource(authErrorMessageRes(errorType)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Button(
                onClick = onLoginClick,
                enabled = !uiState.isLoading && uiState.email.isNotBlank() && uiState.password.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            ) {
                Text(stringResource(R.string.login_button))
            }

            TextButton(
                onClick = onRegisterClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.login_register_prompt))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
    NeverLateTheme {
        LoginScreen(
            uiState = LoginUiState(email = "ada@example.com"),
            onEmailChange = {},
            onPasswordChange = {},
            onLoginClick = {},
            onRegisterClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginScreenErrorPreview() {
    NeverLateTheme {
        LoginScreen(
            uiState = LoginUiState(email = "ada@example.com", errorType = AuthErrorType.INVALID_CREDENTIALS),
            onEmailChange = {},
            onPasswordChange = {},
            onLoginClick = {},
            onRegisterClick = {},
        )
    }
}
