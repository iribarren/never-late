package com.neverlate.data.auth

import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Typed description of the two auth endpoints in `docs/api/contract.md` §2 — same Retrofit
 * pattern [com.neverlate.data.articles.ArticlesApi] introduced in feature 10. Neither call needs
 * (or can have) an `Authorization` header: you cannot attach a Bearer token before the server has
 * ever issued you one, which is exactly why [com.neverlate.data.network.BackendNetwork.create] is
 * used directly for this API (via [AuthNetwork]), with no [com.neverlate.data.sync.AuthInterceptor]
 * — unlike [com.neverlate.data.sync.TasksApi].
 */
interface AuthApi {
    @POST("auth/register")
    suspend fun register(@Body credentials: AuthCredentials): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body credentials: AuthCredentials): AuthResponse
}
