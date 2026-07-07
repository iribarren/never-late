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

/**
 * The three faces of the app's auth state. Feature 11 shipped only [LoggedOut]/[LoggedIn]
 * (account was mandatory); feature 13 (guest mode) adds [Guest] so the app is usable without one:
 *
 * - [LoggedIn]: a valid session; sync is active.
 * - [Guest]: no account; the app is fully usable against the local Room cache and sync is
 *   inactive (`SyncEngine.syncNow` early-returns with no token). This is the **cold-start
 *   default** when no session is stored — see [AuthRepositoryImpl.readInitialAuthState].
 * - [LoggedOut]: a session ended **involuntarily** — a failed silent token refresh (feature 12,
 *   [AuthRepositoryImpl.notifyUnauthorized]) — and routes to the login/register gate.
 *
 * [Guest] and [LoggedOut] are kept deliberately distinct even though both currently render the
 * same *kind* of "no session" fact: [Guest] is a chosen, usable mode ("I don't want an account
 * right now"); [LoggedOut] means "your session just ended, sign back in to get your data back."
 * Conflating them would either reinstate a forced gate for every fresh install, or silently drop
 * an involuntarily-expired user into an empty guest app.
 */
sealed interface AuthState {
    data object LoggedOut : AuthState
    data object Guest : AuthState
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
     * User-initiated sign-out. Best-effort revokes the refresh token server-side (`POST
     * /auth/logout`, contract §2.3), then **unconditionally** clears the session and every
     * locally-cached, backend-owned row (tasks + outbox, and the sync cursor) — same wipe as
     * before feature 13 — but now lands back in [AuthState.Guest] (a usable, empty local mode)
     * instead of [AuthState.LoggedOut].
     *
     * The wipe itself is still mandatory even though guest mode is otherwise local-first: an
     * orphan row (`serverId == null`) surviving logout would be silently re-adopted as a
     * brand-new create the next time *any* account signs in on this device — including a
     * *different* account than the one that just logged out — risking duplication or
     * cross-account leakage (feature 13 spec, PD-2). A failed revoke call (offline, server error)
     * must never prevent the *local* logout from completing (feature 12, US-4).
     *
     * Contrast with [AuthRepositoryImpl.notifyUnauthorized], which shares this same local-clear
     * but lands in [AuthState.LoggedOut] instead, since that path is an *involuntary* session end.
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

    /**
     * Belt-and-braces adoption trigger (feature 13, spec *Risks*): [MainActivity] assigns this to
     * the sync engine's entry point ([com.neverlate.data.tasks.TaskRepository.refreshFromServer])
     * once that repository is built — after which [authenticate] invokes it right after every
     * successful register/login. This does **not** replace the primary adoption mechanism: a
     * `Guest -> LoggedIn` transition already recomposes [com.neverlate.ui.navigation.AppNavHost]
     * into a fresh [com.neverlate.ui.navigation.MainAppNavHost] call site (see its KDoc for why
     * that recomposition reliably re-fires the `LaunchedEffect` that drains the outbox). This
     * property just means adoption does not depend *solely* on that recomposition. Invoking it
     * twice for the same sign-in is harmless: `SyncEngine.syncNow()` is mutex-guarded and a second,
     * near-immediate call simply finds nothing left to push/pull.
     */
    var onAuthenticated: (suspend () -> Unit)? = null

    private fun readInitialAuthState(): AuthState {
        val token = tokenStorage.getAccessToken()
        val userId = tokenStorage.getUserId()
        val email = tokenStorage.getUserEmail()
        return if (token != null && userId != null && email != null) {
            AuthState.LoggedIn(userId, email)
        } else {
            // Feature 13: no stored session no longer means "show the login gate" — it means the
            // app opens straight into a usable, local-only Guest mode (US-1). LoggedOut is now
            // reserved for the involuntary case (see notifyUnauthorized below).
            AuthState.Guest
        }
    }

    override suspend fun register(email: String, password: String): AuthResult =
        authenticate { api.register(AuthCredentials(email, password)) }

    override suspend fun login(email: String, password: String): AuthResult =
        authenticate { api.login(AuthCredentials(email, password)) }

    private suspend fun authenticate(call: suspend () -> AuthResponse): AuthResult = withContext(Dispatchers.IO) {
        try {
            val response = call()
            tokenStorage.saveSession(response.accessToken, response.refreshToken, response.user.id, response.user.email)
            _authState.value = AuthState.LoggedIn(response.user.id, response.user.email)
            // Adoption (feature 13, US-2/US-3): whatever the guest queued in the outbox while
            // tokenless is now pushable, since the token is persisted above. See onAuthenticated's
            // KDoc for why this call is redundant insurance rather than the only trigger.
            onAuthenticated?.invoke()
            AuthResult.Success
        } catch (error: HttpException) {
            AuthResult.Error(mapAuthErrorHttpCode(error.code()))
        } catch (error: IOException) {
            AuthResult.Error(AuthErrorType.NETWORK)
        }
    }

    override suspend fun logout() = withContext(Dispatchers.IO) {
        clearLocalSession()
        _authState.value = AuthState.Guest
    }

    /**
     * Called by [com.neverlate.data.sync.AuthInterceptor] when any `/tasks*` call comes back
     * `401` (expired/invalid token, feature 12 US-2) and silent renewal has already failed. Runs
     * on [scope] rather than the caller's own coroutine, since the interceptor has none to offer.
     *
     * Shares [clearLocalSession] with [logout] — same wipe either way — but lands in
     * [AuthState.LoggedOut] (the auth gate) rather than [AuthState.Guest], since an involuntary
     * session end likely means the user wants to get back to their (server-side) data, not start
     * a fresh local-only one.
     */
    fun notifyUnauthorized() {
        scope.launch {
            clearLocalSession()
            _authState.value = AuthState.LoggedOut
        }
    }

    /**
     * The local-clear internals shared by [logout] (deliberate sign-out) and [notifyUnauthorized]
     * (session invalidated): best-effort revokes the refresh token server-side, then
     * unconditionally wipes the session and every locally-cached, backend-owned row. Deliberately
     * does **not** decide which [AuthState] to land in — that is each caller's own concern.
     */
    private suspend fun clearLocalSession() = withContext(Dispatchers.IO) {
        // Best-effort revoke (contract §2.3): tell the server this refresh token is dead so a
        // stolen copy can't mint new sessions either. A missing token (e.g. a pre-feature-12
        // session that never had one) or a failed call must never block the local clear below.
        val refreshToken = tokenStorage.getRefreshToken()
        if (refreshToken != null) {
            try {
                api.logout(LogoutRequest(refreshToken))
            } catch (error: IOException) {
                // No connectivity — the local session still clears; nothing else to do.
            }
        }

        tokenStorage.clearSession()
        database.withTransaction {
            database.taskDao().clearAll()
            database.outboxDao().clearAll()
        }
        userPreferencesRepository.saveSyncCursor(0L)
    }
}
