package com.neverlate.data.auth

import kotlinx.serialization.Serializable

/** `POST /auth/register` and `POST /auth/login` share this request shape (`docs/api/contract.md` §2). */
@Serializable
data class AuthCredentials(val email: String, val password: String)

/** The `user` object every auth endpoint returns. */
@Serializable
data class UserDto(val id: Long, val email: String)

/**
 * The **token-pair** shape `register`, `login`, and `refresh` all share (`docs/api/contract.md`
 * §2): a short-lived, stateless [accessToken] attached to every `/tasks*` call, plus a long-lived,
 * server-stateful [refreshToken] (feature 12) that is *only* ever sent to `POST /auth/refresh`
 * (§2.2) or `POST /auth/logout` (§2.3) — never to `/tasks*`.
 */
@Serializable
data class AuthResponse(val accessToken: String, val refreshToken: String, val user: UserDto)

/** `POST /auth/refresh` request body (`docs/api/contract.md` §2.2) — no `Authorization` header. */
@Serializable
data class RefreshRequest(val refreshToken: String)

/** `POST /auth/logout` request body (`docs/api/contract.md` §2.3). */
@Serializable
data class LogoutRequest(val refreshToken: String)

/** The error envelope every non-2xx response uses, per the contract's §1.1. */
@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody)

@Serializable
data class ApiErrorBody(val code: String, val message: String)
