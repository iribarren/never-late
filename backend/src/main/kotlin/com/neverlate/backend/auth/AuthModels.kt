package com.neverlate.backend.auth

import kotlinx.serialization.Serializable

/** A user account. `passwordHash` never leaves this file's callers — [UserPublic] is what the
 *  API actually serializes, so a hash can't accidentally end up in a response body or a log
 *  line (contract.md: "Passwords and tokens are never included in any response body or log"). */
data class User(
    val id: Long,
    val email: String,
    val passwordHash: String,
    val createdAt: Long,
)

/** Wire shape for the `user` field of register/login responses — deliberately just `id` +
 *  `email`, never the hash. */
@Serializable
data class UserPublic(val id: Long, val email: String)

fun User.toPublic() = UserPublic(id = id, email = email)

/** Request body shared by `POST /auth/register` and `POST /auth/login` (contract.md §2). */
@Serializable
data class AuthRequest(val email: String, val password: String)

/** Request body for `POST /auth/refresh` (contract.md §2.2). Deliberately not `Authorization`-header
 *  based — the whole point of this endpoint is to work when the access token has already expired. */
@Serializable
data class RefreshRequest(val refreshToken: String)

/** Request body for `POST /auth/logout` (contract.md §2.3). */
@Serializable
data class LogoutRequest(val refreshToken: String)

/** The token-pair response shared by register/login/refresh (contract.md §2): a short-lived
 *  stateless [accessToken] (JWT) plus a long-lived, server-tracked [refreshToken] used only to
 *  mint a new pair later. Kept the pre-feature-12 name `AuthResponse` even though it now carries
 *  two tokens instead of one, since every caller already imports it under this name. */
@Serializable
data class AuthResponse(val accessToken: String, val refreshToken: String, val user: UserPublic)
