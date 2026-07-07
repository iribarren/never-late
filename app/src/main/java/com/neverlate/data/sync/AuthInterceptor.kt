package com.neverlate.data.sync

import com.neverlate.data.auth.TokenStorage
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor for [TasksApi] calls (configured in [TasksNetwork]): attaches
 * `Authorization: Bearer <accessToken>` when a session exists. Deliberately does nothing else —
 * since feature 12, reacting to a `401` (silently renewing via [TokenAuthenticator], falling back
 * to logout only if that fails, US-1/US-2) is that class's job, not this one's. OkHttp invokes an
 * `Authenticator` *after* an interceptor sees a `401`, so this stays the simple "attach whatever
 * token is current" step every request goes through, on the way in.
 */
class AuthInterceptor(
    private val tokenStorage: TokenStorage,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStorage.getAccessToken()
        val request = if (token != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }

        return chain.proceed(request)
    }
}
