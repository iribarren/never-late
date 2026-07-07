package com.neverlate.data.auth

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Typed description of this project's `/auth` endpoints (`docs/api/contract.md` §2) — same
 * Retrofit pattern as [com.neverlate.data.articles.ArticlesApi] introduced in feature 10. None of
 * these carry an `Authorization` header: [register]/[login] happen *before* any token exists, and
 * [refresh] deliberately authenticates with the refresh token in its **body** instead (so it still
 * works once the access token has expired, `docs/api/contract.md` §2.2) — which is exactly why
 * [com.neverlate.data.network.BackendNetwork.create] is used directly for this API (via
 * [AuthNetwork]), with no [com.neverlate.data.sync.AuthInterceptor] or
 * [com.neverlate.data.sync.TokenAuthenticator] attached — unlike [com.neverlate.data.sync.TasksApi].
 */
interface AuthApi {
    @POST("auth/register")
    suspend fun register(@Body credentials: AuthCredentials): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body credentials: AuthCredentials): AuthResponse

    /** Exchanges a refresh token for a brand-new pair — rotation (`docs/api/contract.md` §2.1/§2.2). */
    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): AuthResponse

    /**
     * Revokes a refresh token server-side (`docs/api/contract.md` §2.3). Declared as a raw
     * [Response] rather than a parsed body type because logout is best-effort
     * ([AuthRepositoryImpl.logout]): the caller only cares whether the call completed, not whether
     * the server considered the token already-revoked (both answer `204 No Content`), so there is
     * no body to parse and — unlike [register]/[login]/[refresh] — a non-2xx here should never
     * throw an [retrofit2.HttpException].
     */
    @POST("auth/logout")
    suspend fun logout(@Body request: LogoutRequest): Response<Unit>
}
