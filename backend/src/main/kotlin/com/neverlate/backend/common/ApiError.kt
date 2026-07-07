package com.neverlate.backend.common

import kotlinx.serialization.Serializable

/**
 * The single JSON error envelope every non-2xx response uses (contract.md §1.1):
 * `{ "error": { "code": "...", "message": "..." } }`. Keeping one shape for every failure —
 * instead of a different ad-hoc body per endpoint — is what lets the client parse errors
 * generically instead of special-casing each route.
 */
@Serializable
data class ErrorEnvelope(val error: ErrorBody)

@Serializable
data class ErrorBody(val code: String, val message: String)

/**
 * Sealed hierarchy of "expected" API failures. Each maps to one HTTP status + error `code` from
 * contract.md's table. Routes/services throw these; a single StatusPages handler (see
 * plugins/ErrorHandling.kt) turns them into the JSON envelope so that mapping lives in exactly
 * one place instead of being repeated at every `call.respond(...)`.
 *
 * Anything that is NOT one of these (a genuine bug, a DB hiccup, ...) falls through to the
 * generic 500 `internal_error` branch, which deliberately never leaks the exception message —
 * per contract.md: "never leaks internals".
 */
sealed class ApiException(val statusCode: Int, val code: String, message: String) : Exception(message)

class ValidationException(message: String) : ApiException(400, "validation_error", message)

class InvalidCredentialsException :
    ApiException(401, "invalid_credentials", "Invalid email or password")

/** contract.md §2.1: covers an unknown, expired, revoked, **or reused** (already-rotated) refresh
 *  token — deliberately one code/message for all four cases so a caller can never distinguish
 *  "expired" from "stolen and detected" by the response alone. */
class InvalidRefreshTokenException :
    ApiException(401, "invalid_refresh_token", "Invalid or expired refresh token")

class EmailTakenException :
    ApiException(409, "email_taken", "An account with this email already exists")

class NotFoundException(message: String = "Resource not found") :
    ApiException(404, "not_found", message)
