package com.neverlate.backend.auth

import java.sql.ResultSet
import javax.sql.DataSource

/** Real, Postgres-backed [RefreshTokenRepository] — same plain-JDBC style as
 *  [PostgresUserRepository] / `PostgresTaskRepository` (see db/Database.kt for why no ORM). */
class PostgresRefreshTokenRepository(private val dataSource: DataSource) : RefreshTokenRepository {

    override fun create(userId: Long, familyId: String, tokenHash: String, createdAt: Long, expiresAt: Long): RefreshToken =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO refresh_tokens (user_id, family_id, token_hash, created_at, expires_at, consumed_at, revoked)
                VALUES (?, ?, ?, ?, ?, NULL, FALSE)
                RETURNING id
                """.trimIndent(),
            ).use { stmt ->
                stmt.setLong(1, userId)
                stmt.setString(2, familyId)
                stmt.setString(3, tokenHash)
                stmt.setLong(4, createdAt)
                stmt.setLong(5, expiresAt)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    RefreshToken(
                        id = rs.getLong("id"),
                        userId = userId,
                        familyId = familyId,
                        tokenHash = tokenHash,
                        createdAt = createdAt,
                        expiresAt = expiresAt,
                        consumedAt = null,
                        revoked = false,
                    )
                }
            }
        }

    override fun findByTokenHash(tokenHash: String): RefreshToken? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, user_id, family_id, token_hash, created_at, expires_at, consumed_at, revoked
                FROM refresh_tokens WHERE token_hash = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, tokenHash)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.toRefreshToken() else null }
            }
        }

    override fun markConsumedIfUnconsumed(id: Long, consumedAt: Long): Boolean =
        dataSource.connection.use { conn ->
            // The `AND consumed_at IS NULL` makes this a single atomic compare-and-set: Postgres
            // row-level locking serializes two concurrent UPDATEs against the same row, and the
            // loser's WHERE clause re-evaluates (READ COMMITTED) against the winner's already-committed
            // `consumed_at`, so it matches zero rows instead of clobbering the winner's write.
            conn.prepareStatement(
                "UPDATE refresh_tokens SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL",
            ).use { stmt ->
                stmt.setLong(1, consumedAt)
                stmt.setLong(2, id)
                stmt.executeUpdate() > 0
            }
        }

    override fun revokeById(id: Long) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE refresh_tokens SET revoked = TRUE WHERE id = ?").use { stmt ->
                stmt.setLong(1, id)
                stmt.executeUpdate()
            }
        }
    }

    override fun revokeFamily(familyId: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE refresh_tokens SET revoked = TRUE WHERE family_id = ?").use { stmt ->
                stmt.setString(1, familyId)
                stmt.executeUpdate()
            }
        }
    }

    private fun ResultSet.toRefreshToken(): RefreshToken {
        // wasNull() reports on whatever getXxx call happened *last*, so it must be read
        // immediately after getLong("consumed_at") — not after later columns are fetched inside
        // the constructor call below (Kotlin evaluates named arguments in call order).
        val consumedAtValue = getLong("consumed_at")
        val consumedAtWasNull = wasNull()
        return RefreshToken(
            id = getLong("id"),
            userId = getLong("user_id"),
            familyId = getString("family_id"),
            tokenHash = getString("token_hash"),
            createdAt = getLong("created_at"),
            expiresAt = getLong("expires_at"),
            consumedAt = if (consumedAtWasNull) null else consumedAtValue,
            revoked = getBoolean("revoked"),
        )
    }
}
