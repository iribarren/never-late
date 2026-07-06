package com.neverlate.data.auth

import com.neverlate.data.network.BackendNetwork
import com.neverlate.data.network.DEFAULT_BACKEND_BASE_URL

/**
 * Builds a ready-to-use [AuthApi]. Kept as a tiny factory object — same reasoning as
 * [com.neverlate.data.articles.ArticlesNetwork] — so [com.neverlate.MainActivity] has one obvious
 * place to call, and tests can point [baseUrl] at a local `MockWebServer` instead.
 */
object AuthNetwork {
    fun create(baseUrl: String = DEFAULT_BACKEND_BASE_URL): AuthApi =
        BackendNetwork.create(AuthApi::class.java, baseUrl)
}
