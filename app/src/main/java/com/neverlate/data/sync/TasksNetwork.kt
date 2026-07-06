package com.neverlate.data.sync

import com.neverlate.data.auth.TokenStorage
import com.neverlate.data.network.BackendNetwork
import com.neverlate.data.network.DEFAULT_BACKEND_BASE_URL

/**
 * Builds a ready-to-use [TasksApi], with [AuthInterceptor] wired in so every call carries the
 * current session's Bearer token and a `401` routes back to [onUnauthorized]. Same tiny-factory
 * pattern as [com.neverlate.data.auth.AuthNetwork]/[com.neverlate.data.articles.ArticlesNetwork].
 */
object TasksNetwork {
    fun create(
        baseUrl: String = DEFAULT_BACKEND_BASE_URL,
        tokenStorage: TokenStorage,
        onUnauthorized: () -> Unit,
    ): TasksApi = BackendNetwork.create(
        TasksApi::class.java,
        baseUrl,
        extraInterceptors = listOf(AuthInterceptor(tokenStorage, onUnauthorized)),
    )
}
