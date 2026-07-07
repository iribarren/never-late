package com.neverlate.backend.auth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Test fake for [RefreshTokenRepository]: no SQL, no Docker — a thread-safe in-memory map, same
 * trick as [InMemoryUserRepository].
 */
class InMemoryRefreshTokenRepository : RefreshTokenRepository {
    private val tokensById = ConcurrentHashMap<Long, RefreshToken>()
    private val nextId = AtomicLong(1)

    override fun create(userId: Long, familyId: String, tokenHash: String, createdAt: Long, expiresAt: Long): RefreshToken {
        val token = RefreshToken(
            id = nextId.getAndIncrement(),
            userId = userId,
            familyId = familyId,
            tokenHash = tokenHash,
            createdAt = createdAt,
            expiresAt = expiresAt,
            consumedAt = null,
            revoked = false,
        )
        tokensById[token.id] = token
        return token
    }

    override fun findByTokenHash(tokenHash: String): RefreshToken? =
        tokensById.values.find { it.tokenHash == tokenHash }

    override fun markConsumedIfUnconsumed(id: Long, consumedAt: Long): Boolean {
        // ConcurrentHashMap.computeIfPresent applies its remapping function atomically per key
        // (holding the bin lock for the duration), so this mirrors the Postgres implementation's
        // atomic compare-and-set: only the first of two concurrent callers sees consumedAt == null
        // and wins.
        var won = false
        tokensById.computeIfPresent(id) { _, token ->
            if (token.consumedAt == null) {
                won = true
                token.copy(consumedAt = consumedAt)
            } else {
                token
            }
        }
        return won
    }

    override fun revokeById(id: Long) {
        tokensById.computeIfPresent(id) { _, token -> token.copy(revoked = true) }
    }

    override fun revokeFamily(familyId: String) {
        tokensById.replaceAll { _, token ->
            if (token.familyId == familyId) token.copy(revoked = true) else token
        }
    }
}
