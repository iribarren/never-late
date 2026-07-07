package com.neverlate.backend.auth

import com.neverlate.backend.Config
import com.neverlate.backend.common.InvalidCredentialsException
import com.neverlate.backend.common.InvalidRefreshTokenException
import com.neverlate.backend.common.ValidationException
import java.util.UUID
import org.slf4j.LoggerFactory

/** A conservative but real email shape check — not RFC 5322-complete, but enough to reject
 *  obviously malformed input server-side (contract.md: validation must exist server-side, not
 *  only in the client form). */
private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
private const val MIN_PASSWORD_LENGTH = 8
private val logger = LoggerFactory.getLogger("AuthService")

/** Business logic for registration/login/refresh/logout. Depends only on [UserRepository] and
 *  [RefreshTokenRepository] (the seams), so it is exercised in tests against their in-memory fakes
 *  without a real database. */
class AuthService(
    private val users: UserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val config: Config,
) {

    fun register(email: String, password: String): AuthResponse {
        validate(email, password)
        val passwordHash = PasswordHasher.hash(password)
        val user = users.create(email = email, passwordHash = passwordHash, createdAt = System.currentTimeMillis())
        // Register starts a brand-new refresh-token family — spec decision #3: register issues a
        // refresh token too, exactly like login.
        return issueNewSession(user)
    }

    fun login(email: String, password: String): AuthResponse {
        validate(email, password)
        val user = users.findByEmail(email) ?: throw InvalidCredentialsException()
        // Same exception for "unknown email" and "wrong password" (contract.md §2 `POST
        // /auth/login`) — this is deliberate: revealing *which* case it was would let an
        // attacker enumerate registered emails.
        if (!PasswordHasher.verify(password, user.passwordHash)) throw InvalidCredentialsException()
        return issueNewSession(user)
    }

    /**
     * Exchanges a presented refresh token for a brand-new pair (contract.md §2.2). This is the
     * one place the "stateless JWT" story gets an asterisk: unlike verifying an access token (pure
     * signature math), this requires a database round-trip, because a refresh token's validity
     * depends on server-side state (has it been rotated already? revoked? expired?) that no
     * signature alone can capture.
     */
    fun refresh(rawRefreshToken: String): AuthResponse {
        if (rawRefreshToken.isBlank()) throw ValidationException("refreshToken is required")
        val stored = refreshTokens.findByTokenHash(RefreshTokenHasher.hash(rawRefreshToken))
            ?: throw InvalidRefreshTokenException()
        val now = System.currentTimeMillis()

        if (stored.revoked) throw InvalidRefreshTokenException()
        if (stored.consumedAt != null) {
            // Reuse (theft): this exact token was already exchanged once before. A legitimate
            // client would only ever hold the *latest* token in a lineage, so a second exchange
            // means the presented token leaked to someone else — kill the whole family (contract.md
            // §2.1) rather than just this row, since the thief's own freshly-rotated token (a
            // sibling in the same family) must die too.
            //
            // Checked *before* expiry (below) deliberately: reuse of an already-rotated token must
            // kill the family even if this particular stale token has since expired — otherwise a
            // thief who waits out the presented token's lifetime before replaying it would slip
            // past reuse detection entirely while its still-live sibling (the legitimate user's
            // current token) survives.
            logger.warn("Refresh token reuse detected: userId=${stored.userId} familyId=${stored.familyId}")
            refreshTokens.revokeFamily(stored.familyId)
            throw InvalidRefreshTokenException()
        }
        if (stored.expiresAt < now) throw InvalidRefreshTokenException()

        // Atomically claim this token for rotation (see RefreshTokenRepository.markConsumedIfUnconsumed):
        // closes a TOCTOU race where two concurrent requests both read consumedAt == null above and
        // would otherwise both be allowed to rotate the same token, minting two live children with
        // no reuse ever detected.
        if (!refreshTokens.markConsumedIfUnconsumed(stored.id, now)) {
            logger.warn(
                "Refresh token reuse detected (concurrent consume race): userId=${stored.userId} familyId=${stored.familyId}",
            )
            refreshTokens.revokeFamily(stored.familyId)
            throw InvalidRefreshTokenException()
        }

        // The user row could in principle have been deleted since this token was issued; treating
        // that as an invalid refresh (rather than a 500) keeps the failure mode consistent.
        val user = users.findById(stored.userId) ?: throw InvalidRefreshTokenException()
        return issuePair(user, stored.familyId)
    }

    /** `POST /auth/logout` (contract.md §2.3): best-effort, idempotent, never reveals whether the
     *  token was valid — an unknown/already-revoked token is silently a no-op. */
    fun revoke(rawRefreshToken: String) {
        if (rawRefreshToken.isBlank()) throw ValidationException("refreshToken is required")
        val stored = refreshTokens.findByTokenHash(RefreshTokenHasher.hash(rawRefreshToken)) ?: return
        refreshTokens.revokeById(stored.id)
    }

    /** Register/login both start a **new** lineage (a fresh [UUID] family id) — they are not
     *  rotations of anything, so they must never inherit an existing family. */
    private fun issueNewSession(user: User): AuthResponse = issuePair(user, familyId = UUID.randomUUID().toString())

    private fun issuePair(user: User, familyId: String): AuthResponse {
        val now = System.currentTimeMillis()
        val rawRefreshToken = RefreshTokenGenerator.generate()
        refreshTokens.create(
            userId = user.id,
            familyId = familyId,
            tokenHash = RefreshTokenHasher.hash(rawRefreshToken),
            createdAt = now,
            expiresAt = now + config.refreshTokenExpiryDays * 24 * 60 * 60 * 1000,
        )
        return AuthResponse(
            accessToken = Jwt.createToken(config, user.id),
            refreshToken = rawRefreshToken,
            user = user.toPublic(),
        )
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
