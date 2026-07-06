package com.neverlate.data.auth

import kotlinx.serialization.Serializable

/** `POST /auth/register` and `POST /auth/login` share this request shape (`docs/api/contract.md` §2). */
@Serializable
data class AuthCredentials(val email: String, val password: String)

/** The `user` object both auth endpoints return. */
@Serializable
data class UserDto(val id: Long, val email: String)

/** The response shape both `register` and `login` share on success. */
@Serializable
data class AuthResponse(val token: String, val user: UserDto)

/** The error envelope every non-2xx response uses, per the contract's §1.1. */
@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody)

@Serializable
data class ApiErrorBody(val code: String, val message: String)
