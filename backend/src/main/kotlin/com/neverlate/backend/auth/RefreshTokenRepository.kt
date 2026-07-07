package com.neverlate.backend.auth

/**
 * A persisted refresh token (contract.md §2.1). Unlike the access token (a self-contained,
 * stateless JWT), the server keeps a row per refresh token so it can be rotated, revoked, and
 * checked for reuse. [tokenHash] is the only form of the token that ever reaches storage — see
 * [RefreshTokenHasher].
 *
 * [familyId] links every token descended from a single login/register through however many
 * rotations have happened since (a "lineage"). It exists purely to bound the blast radius of
 * reuse detection: when a stolen token is replayed, the server can kill *that* lineage without
 * touching other devices/sessions the same user has open (contract.md §2.1, spec decision #2).
 */
data class RefreshToken(
    val id: Long,
    val userId: Long,
    val familyId: String,
    val tokenHash: String,
    val createdAt: Long,
    val expiresAt: Long,
    /** Set the moment this token is exchanged at `/auth/refresh` (rotated). A non-null value on a
     *  token presented *again* is the signal for reuse detection (contract.md §2.1). */
    val consumedAt: Long?,
    /** Set by an explicit `/auth/logout` (this token only) or a reuse-triggered family kill (every
     *  token sharing [familyId]). */
    val revoked: Boolean,
)

/**
 * The repository seam for refresh tokens — same pattern as [UserRepository]: [AuthService]
 * depends only on this interface, never on SQL or a specific store, so tests can run against
 * [InMemoryRefreshTokenRepository] with no Docker/Postgres, while [PostgresRefreshTokenRepository]
 * backs the real deployment.
 */
interface RefreshTokenRepository {
    /** Persists a brand-new token row. [familyId] is a fresh UUID for a new login/register, or the
     *  same id carried forward from the token being rotated. */
    fun create(userId: Long, familyId: String, tokenHash: String, createdAt: Long, expiresAt: Long): RefreshToken

    fun findByTokenHash(tokenHash: String): RefreshToken?

    /**
     * Atomically marks a token consumed (rotated) **only if it had not already been consumed**,
     * returning whether this call won that race. Must be a single conditional write (e.g. SQL
     * `UPDATE ... WHERE consumed_at IS NULL`), not a separate read-then-write, so that two
     * concurrent `/auth/refresh` calls presenting the same still-valid token can never both
     * succeed: exactly one call receives `true` (and proceeds to rotate), the other receives
     * `false` and must be treated as reuse (see [AuthService.refresh]). Without this atomicity, a
     * plain check-then-set has a window where both callers observe "not yet consumed" and both
     * mint a new pair, defeating rotation and reuse detection under concurrency.
     */
    fun markConsumedIfUnconsumed(id: Long, consumedAt: Long): Boolean

    /** Revokes exactly this token (used by logout — contract.md §2.3). */
    fun revokeById(id: Long)

    /** Revokes every token in [familyId], regardless of its individual state — the reuse-detection
     *  response to a stolen, already-rotated token being replayed (contract.md §2.1). */
    fun revokeFamily(familyId: String)
}
