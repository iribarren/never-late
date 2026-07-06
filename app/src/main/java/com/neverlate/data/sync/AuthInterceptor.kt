package com.neverlate.data.sync

import com.neverlate.data.auth.TokenStorage
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor for [TasksApi] calls (configured in [TasksNetwork]): attaches
 * `Authorization: Bearer <token>` when a session exists, and reports a `401` response back to
 * [onUnauthorized] so the caller can log the user out (US-2 of the feature spec — an
 * expired/invalid token always routes back to login).
 *
 * [onUnauthorized] is a plain callback, not a suspend function: OkHttp interceptors run
 * synchronously on one of OkHttp's own dispatcher threads, never inside a coroutine, so whatever
 * this calls (see [com.neverlate.data.auth.AuthRepositoryImpl.notifyUnauthorized]) must launch its
 * own coroutine if it needs to suspend.
 */
class AuthInterceptor(
    private val tokenStorage: TokenStorage,
    private val onUnauthorized: () -> Unit,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStorage.getToken()
        val request = if (token != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }

        val response = chain.proceed(request)
        if (response.code == 401) {
            onUnauthorized()
        }
        return response
    }
}
