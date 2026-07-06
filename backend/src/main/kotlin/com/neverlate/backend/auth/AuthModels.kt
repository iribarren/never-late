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

/** Response body shared by both endpoints' success cases. */
@Serializable
data class AuthResponse(val token: String, val user: UserPublic)
