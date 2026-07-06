package com.neverlate.data.auth

import androidx.room.withTransaction
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.tasks.NeverLateDatabase
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Whether there is a logged-in session, and who it belongs to. */
sealed interface AuthState {
    data object LoggedOut : AuthState
    data class LoggedIn(val userId: Long, val email: String) : AuthState
}

/** The outcome of a [AuthRepository.register]/[AuthRepository.login] attempt. */
sealed interface AuthResult {
    data object Success : AuthResult
    data class Error(val type: AuthErrorType) : AuthResult
}

/**
 * Registers/logs in/logs out, and exposes the current [AuthState] — the account counterpart of
 * [com.neverlate.data.tasks.TaskRepository]: an interface so the auth-gate UI
 * ([com.neverlate.ui.auth.LoginViewModel]/[com.neverlate.ui.auth.RegisterViewModel]) and
 * [com.neverlate.ui.settings.SettingsViewModel] (logout) depend only on this contract, never on
 * [AuthRepositoryImpl] or Retrofit/[TokenStorage] directly.
 */
interface AuthRepository {
    val authState: StateFlow<AuthState>

    suspend fun register(email: String, password: String): AuthResult

    suspend fun login(email: String, password: String): AuthResult

    /**
     * Clears the session and every locally-cached, backend-owned row (tasks + outbox, and the
     * sync cursor) — since account is mandatory in this feature (see the spec's *Out of Scope*),
     * there is no "local-only" data left behind to preserve; the cache simply repopulates from
     * the server the next time someone logs in.
     */
    suspend fun logout()
}

/**
 * Real implementation: [api] talks to the `/auth` endpoints, [tokenStorage] persists the session,
 * and [database]/[userPreferencesRepository] are what [logout] wipes.
 */
class AuthRepositoryImpl(
    private val api: AuthApi,
    private val tokenStorage: TokenStorage,
    private val database: NeverLateDatabase,
    private val userPreferencesRepository: UserPreferencesRepository,
) : AuthRepository {

    // Fire-and-forget logout needs its own scope: it is triggered from
    // com.neverlate.data.sync.AuthInterceptor, which runs on an OkHttp thread with no coroutine
    // (and no ViewModel) of its own — see notifyUnauthorized.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _authState = MutableStateFlow(readInitialAuthState())
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private fun readInitialAuthState(): AuthState {
        val token = tokenStorage.getToken()
        val userId = tokenStorage.getUserId()
        val email = tokenStorage.getUserEmail()
        return if (token != null && userId != null && email != null) {
            AuthState.LoggedIn(userId, email)
        } else {
            AuthState.LoggedOut
        }
    }

    override suspend fun register(email: String, password: String): AuthResult =
        authenticate { api.register(AuthCredentials(email, password)) }

    override suspend fun login(email: String, password: String): AuthResult =
        authenticate { api.login(AuthCredentials(email, password)) }

    private suspend fun authenticate(call: suspend () -> AuthResponse): AuthResult = withContext(Dispatchers.IO) {
        try {
            val response = call()
            tokenStorage.saveSession(response.token, response.user.id, response.user.email)
            _authState.value = AuthState.LoggedIn(response.user.id, response.user.email)
            AuthResult.Success
        } catch (error: HttpException) {
            AuthResult.Error(mapAuthErrorHttpCode(error.code()))
        } catch (error: IOException) {
            AuthResult.Error(AuthErrorType.NETWORK)
        }
    }

    override suspend fun logout() {
        tokenStorage.clearSession()
        database.withTransaction {
            database.taskDao().clearAll()
            database.outboxDao().clearAll()
        }
        userPreferencesRepository.saveSyncCursor(0L)
        _authState.value = AuthState.LoggedOut
    }

    /**
     * Called by [com.neverlate.data.sync.AuthInterceptor] when any `/tasks*` call comes back
     * `401` (expired/invalid token, US-2). Runs on [scope] rather than the caller's own coroutine,
     * since the interceptor has none to offer.
     */
    fun notifyUnauthorized() {
        scope.launch { logout() }
    }
}
