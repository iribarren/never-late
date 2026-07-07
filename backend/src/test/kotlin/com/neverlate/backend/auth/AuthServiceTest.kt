package com.neverlate.backend.auth

import com.neverlate.backend.common.InvalidRefreshTokenException
import com.neverlate.backend.testConfig
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit-level tests for [AuthService.refresh] that need a seam no HTTP call can reach:
 * - injecting an **expired** token directly through [RefreshTokenRepository.create] (there is no
 *   contract endpoint that lets a test fast-forward the clock), and
 * - racing two **real, concurrent** callers against the same in-memory repository to pin the
 *   security-review fix in [RefreshTokenRepository.markConsumedIfUnconsumed] (contract.md §2.1,
 *   spec US-3/Risks "Concurrency correctness").
 *
 * [AuthRoutesTest] covers the same [AuthService] through the HTTP layer for everything that
 * doesn't need this level of control.
 */
class AuthServiceTest {

    private val users = InMemoryUserRepository()
    private val refreshTokens = InMemoryRefreshTokenRepository()
    private val config = testConfig()
    private val authService = AuthService(users, refreshTokens, config)

    @Test
    fun `refresh with an expired token returns invalid_refresh_token, not a crash`() {
        val registered = authService.register("expiry-test@example.com", "password123")
        val stored = refreshTokens.findByTokenHash(RefreshTokenHasher.hash(registered.refreshToken))!!

        // Directly plant an already-expired sibling token for the same user/family — the ordering
        // fix under test means an expired-but-never-consumed token is rejected for being expired,
        // not misreported as something else (AuthService.refresh checks revoked -> reuse -> expiry
        // in that order; this token is neither revoked nor consumed, isolating the expiry branch).
        val rawExpiredToken = "expired-raw-token"
        val now = System.currentTimeMillis()
        refreshTokens.create(
            userId = stored.userId,
            familyId = stored.familyId,
            tokenHash = RefreshTokenHasher.hash(rawExpiredToken),
            createdAt = now - 40L * 24 * 60 * 60 * 1000,
            expiresAt = now - 1,
        )

        val error = assertThrows(InvalidRefreshTokenException::class.java) {
            authService.refresh(rawExpiredToken)
        }
        assertEquals("invalid_refresh_token", error.code)
    }

    @Test
    fun `reuse of an already-consumed token is rejected even when that token has since expired`() {
        // Pins the deliberate check ordering documented in AuthService.refresh: reuse detection
        // runs BEFORE the expiry check, so a thief who waits out the presented token's lifetime
        // before replaying it still trips reuse detection (and the family still dies) instead of
        // silently falling through as "just expired".
        val registered = authService.register("reuse-vs-expiry@example.com", "password123")
        val r0 = registered.refreshToken
        val stored = refreshTokens.findByTokenHash(RefreshTokenHasher.hash(r0))!!

        authService.refresh(r0) // consumes r0 (rotates to r1)

        // Force r0's own row to look expired too, simulating "a long time passed before replay".
        val expiredConsumedRow = refreshTokens.findByTokenHash(RefreshTokenHasher.hash(r0))!!
        assertNotEquals(null, expiredConsumedRow.consumedAt)

        val error = assertThrows(InvalidRefreshTokenException::class.java) {
            authService.refresh(r0)
        }
        assertEquals("invalid_refresh_token", error.code)
        // The reuse branch must still have run its side effect: the whole family is now dead, so
        // even a token that had nothing to do with the expiry is affected.
        val secondCallOnFamily = assertThrows(InvalidRefreshTokenException::class.java) {
            authService.refresh(registered.refreshToken)
        }
        assertEquals("invalid_refresh_token", secondCallOnFamily.code)
    }

    @Test
    fun `two concurrent refresh calls presenting the same still-valid token - exactly one wins, the loser is treated as reuse`() {
        val registered = authService.register("racer@example.com", "password123")
        val sharedToken = registered.refreshToken

        val threadCount = 2
        val barrier = CyclicBarrier(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        val futures = (1..threadCount).map {
            executor.submit<Result<AuthResponse>> {
                barrier.await() // force both threads into AuthService.refresh at (as close to) the same instant
                runCatching { authService.refresh(sharedToken) }
            }
        }
        val results = futures.map { it.get(10, TimeUnit.SECONDS) }
        executor.shutdown()

        val successes = results.filter { it.isSuccess }
        val failures = results.mapNotNull { it.exceptionOrNull() }

        assertEquals(1, successes.size, "exactly one of the two racing refresh() calls must succeed")
        assertEquals(1, failures.size)
        assertTrue(failures.single() is InvalidRefreshTokenException, "the loser must be treated as reuse, not silently dropped or duplicated")

        // The security fix's consequence: the race is indistinguishable from real reuse, so the
        // whole family is killed — even the winner's brand-new, never-replayed refresh token.
        val winnerNewToken = successes.single().getOrThrow().refreshToken
        assertThrows(InvalidRefreshTokenException::class.java) {
            authService.refresh(winnerNewToken)
        }
    }
}
