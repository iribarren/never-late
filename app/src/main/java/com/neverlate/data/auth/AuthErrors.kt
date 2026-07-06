package com.neverlate.data.auth

/**
 * Every way [AuthRepository.register]/[AuthRepository.login] can fail, mapped from the backend's
 * HTTP status (`docs/api/contract.md` §1.1/§2) to a value the UI can turn into a localized,
 * readable message — same "no string literals outside `strings.xml`, no raw exception shown to
 * the user" convention as [com.neverlate.data.tasks.TaskValidationError].
 */
enum class AuthErrorType {
    /** 409 on register: the email is already taken. */
    EMAIL_TAKEN,

    /** 401 on login: wrong email or password (the API deliberately does not say which). */
    INVALID_CREDENTIALS,

    /** 400: the server rejected the request body (e.g. password too short). */
    VALIDATION,

    /** No connectivity, timeout, DNS failure... — never the server's fault. */
    NETWORK,

    /** Anything else (5xx, an unexpected status). */
    UNKNOWN,
}

/**
 * Pure mapping from an HTTP status code to an [AuthErrorType] — kept as a standalone function
 * (rather than inline in [AuthRepository]) so it is trivially unit-testable on the plain JVM,
 * with no network/Retrofit involved, same spirit as [com.neverlate.data.tasks.validateTaskForm].
 */
fun mapAuthErrorHttpCode(httpCode: Int): AuthErrorType = when (httpCode) {
    409 -> AuthErrorType.EMAIL_TAKEN
    401 -> AuthErrorType.INVALID_CREDENTIALS
    400 -> AuthErrorType.VALIDATION
    else -> AuthErrorType.UNKNOWN
}
