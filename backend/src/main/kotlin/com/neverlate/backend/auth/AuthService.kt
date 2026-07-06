package com.neverlate.backend.auth

import com.neverlate.backend.Config
import com.neverlate.backend.common.InvalidCredentialsException
import com.neverlate.backend.common.ValidationException

/** A conservative but real email shape check — not RFC 5322-complete, but enough to reject
 *  obviously malformed input server-side (contract.md: validation must exist server-side, not
 *  only in the client form). */
private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
private const val MIN_PASSWORD_LENGTH = 8

/** Business logic for registration/login. Depends only on [UserRepository] (the seam), so it
 *  is exercised in tests against [InMemoryUserRepository] without a real database. */
class AuthService(private val users: UserRepository, private val config: Config) {

    fun register(email: String, password: String): AuthResponse {
        validate(email, password)
        val passwordHash = PasswordHasher.hash(password)
        val user = users.create(email = email, passwordHash = passwordHash, createdAt = System.currentTimeMillis())
        return AuthResponse(token = Jwt.createToken(config, user.id), user = user.toPublic())
    }

    fun login(email: String, password: String): AuthResponse {
        validate(email, password)
        val user = users.findByEmail(email) ?: throw InvalidCredentialsException()
        // Same exception for "unknown email" and "wrong password" (contract.md §2 `POST
        // /auth/login`) — this is deliberate: revealing *which* case it was would let an
        // attacker enumerate registered emails.
        if (!PasswordHasher.verify(password, user.passwordHash)) throw InvalidCredentialsException()
        return AuthResponse(token = Jwt.createToken(config, user.id), user = user.toPublic())
    }

    private fun validate(email: String, password: String) {
        if (!EMAIL_REGEX.matches(email)) {
            throw ValidationException("Email must be a valid email address")
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw ValidationException("Password must be at least $MIN_PASSWORD_LENGTH characters")
        }
    }
}
