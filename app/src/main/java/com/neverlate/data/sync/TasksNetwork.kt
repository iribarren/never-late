package com.neverlate.data.sync

import com.neverlate.data.auth.AuthNetwork
import com.neverlate.data.auth.TokenStorage
import com.neverlate.data.network.BackendNetwork
import com.neverlate.data.network.DEFAULT_BACKEND_BASE_URL

/**
 * Builds a ready-to-use [TasksApi]: [AuthInterceptor] attaches the current session's Bearer token
 * on every call, and [TokenAuthenticator] renews it transparently (feature 12, US-1) whenever a
 * call comes back `401`, falling back to [onUnauthorized] only once renewal itself is impossible
 * (US-2). Same tiny-factory pattern as [com.neverlate.data.auth.AuthNetwork]/
 * [com.neverlate.data.articles.ArticlesNetwork].
 */
object TasksNetwork {
    fun create(
        baseUrl: String = DEFAULT_BACKEND_BASE_URL,
        tokenStorage: TokenStorage,
        onUnauthorized: () -> Unit,
    ): TasksApi = BackendNetwork.create(
        TasksApi::class.java,
        baseUrl,
        extraInterceptors = listOf(AuthInterceptor(tokenStorage)),
        authenticator = TokenAuthenticator(
            // A bare AuthApi with no interceptor/authenticator of its own (see AuthNetwork's
            // KDoc) — required so a failed refresh call can't recurse back into this client.
            refreshApi = AuthNetwork.create(baseUrl),
            tokenStorage = tokenStorage,
            onRefreshFailed = onUnauthorized,
        ),
    )
}
