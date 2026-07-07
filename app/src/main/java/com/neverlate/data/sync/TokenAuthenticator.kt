package com.neverlate.data.sync

import com.neverlate.data.auth.AuthApi
import com.neverlate.data.auth.RefreshRequest
import com.neverlate.data.auth.TokenStorage
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException

private const val BEARER_PREFIX = "Bearer "

/**
 * NEW CONCEPT (feature 12): an OkHttp [Authenticator] is a different extension point from an
 * [Interceptor][okhttp3.Interceptor] like [AuthInterceptor]. OkHttp calls an `Authenticator`
 * *specifically* when a response comes back `401`, hands it the failed [Response] — including the
 * chain of prior retries via [Response.priorResponse] — and automatically retries with whatever
 * [Request] it returns; returning `null` gives up and the `401` reaches the original caller
 * unchanged. This is the idiomatic place for "renew the access token and retry the request", which
 * is exactly what silent session renewal (US-1 of the feature spec) needs.
 *
 * [refreshApi] MUST be built with no [AuthInterceptor]/[TokenAuthenticator] of its own (see
 * [com.neverlate.data.auth.AuthNetwork.create], used as-is in [TasksNetwork]) — otherwise a `401`
 * from `POST /auth/refresh` itself would recurse back into this same class.
 */
class TokenAuthenticator(
    private val refreshApi: AuthApi,
    private val tokenStorage: TokenStorage,
    private val onRefreshFailed: () -> Unit,
) : Authenticator {

    // Single-flight guard (US-3): a burst of concurrent 401s (typical mid-sync, when push and
    // pull requests are both in flight) must share ONE refresh call, not one each. With rotation
    // (contract §2.1), a second *concurrent* refresh would present a refresh token the first call
    // already consumed — the server would treat that as reuse (theft) and kill the whole session
    // for no real reason. `synchronized` (not a coroutine `Mutex`) is enough here: `authenticate`
    // already runs synchronously on one of OkHttp's own dispatcher threads, never inside a
    // coroutine — same assumption [AuthInterceptor.intercept] makes.
    private val refreshLock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Bound the retry chain (US-2, and the "retry storm" risk called out in the spec): only
        // ever retry once. If the *retried* request also comes back 401, refreshing again would
        // just loop forever instead of ever reaching the caller.
        if (responseCount(response) > 1) {
            onRefreshFailed()
            return null
        }

        val failedAccessToken = bearerToken(response.request)

        synchronized(refreshLock) {
            val currentAccessToken = tokenStorage.getAccessToken()
            if (currentAccessToken != null && currentAccessToken != failedAccessToken) {
                // Another thread already refreshed while this one was waiting for the lock: the
                // token stored now is no longer the one that failed, so just retry with it — no
                // second refresh for the same expired token generation.
                return requestWithBearer(response.request, currentAccessToken)
            }

            val refreshToken = tokenStorage.getRefreshToken()
            if (refreshToken == null) {
                // No refresh token to renew with at all (logged out, or a session from before
                // feature 12 — see the spec's *Out of Scope*). Renewal is impossible; fall back.
                onRefreshFailed()
                return null
            }

            return try {
                // Blocking is safe (and necessary) here: `authenticate` is a synchronous OkHttp
                // callback with no coroutine of its own to suspend from — the same reasoning
                // AuthInterceptor.intercept already relies on elsewhere in this package.
                val newTokens = runBlocking { refreshApi.refresh(RefreshRequest(refreshToken)) }
                // Atomic swap (US-4): both tokens must land together, see TokenStorage.saveTokens.
                tokenStorage.saveTokens(newTokens.accessToken, newTokens.refreshToken)
                requestWithBearer(response.request, newTokens.accessToken)
            } catch (error: HttpException) {
                // 401 invalid_refresh_token (contract §2.2): expired, revoked, or reused. Renewal
                // is genuinely impossible — fall back to the pre-feature-12 behaviour (US-2): log
                // out and route to login.
                onRefreshFailed()
                null
            } catch (error: IOException) {
                // No connectivity to even attempt renewal. Give up for *now* without logging the
                // user out over a transient network error — the next foreground call or the
                // periodic SyncWorker backstop gets another chance.
                null
            }
        }
    }

    private fun bearerToken(request: Request): String? =
        request.header("Authorization")?.removePrefix(BEARER_PREFIX)

    private fun requestWithBearer(request: Request, accessToken: String): Request =
        request.newBuilder()
            .header("Authorization", "$BEARER_PREFIX$accessToken")
            .build()

    /** Counts this response plus every [Response.priorResponse] before it, oldest call = 1. */
    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
