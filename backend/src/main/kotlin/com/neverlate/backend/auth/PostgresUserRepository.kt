package com.neverlate.backend.auth

import com.neverlate.backend.common.EmailTakenException
import java.sql.SQLException
import javax.sql.DataSource

/** Postgres' error code for a unique-constraint violation (SQLSTATE 23505). Checking this
 *  instead of pre-checking "does this email exist" avoids a race between two concurrent
 *  registrations for the same email (classic check-then-act bug). */
private const val UNIQUE_VIOLATION_SQLSTATE = "23505"

class PostgresUserRepository(private val dataSource: DataSource) : UserRepository {

    override fun findByEmail(email: String): User? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT id, email, password_hash, created_at FROM users WHERE email = ?",
            ).use { stmt ->
                stmt.setString(1, email)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toUser() else null
                }
            }
        }

    override fun findById(id: Long): User? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT id, email, password_hash, created_at FROM users WHERE id = ?",
            ).use { stmt ->
                stmt.setLong(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toUser() else null
                }
            }
        }

    override fun create(email: String, passwordHash: String, createdAt: Long): User {
        try {
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "INSERT INTO users (email, password_hash, created_at) VALUES (?, ?, ?) RETURNING id",
                ).use { stmt ->
                    stmt.setString(1, email)
                    stmt.setString(2, passwordHash)
                    stmt.setLong(3, createdAt)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        val id = rs.getLong("id")
                        return User(id = id, email = email, passwordHash = passwordHash, createdAt = createdAt)
                    }
                }
            }
        } catch (e: SQLException) {
            if (e.sqlState == UNIQUE_VIOLATION_SQLSTATE) throw EmailTakenException()
            throw e
        }
    }

    private fun java.sql.ResultSet.toUser() = User(
        id = getLong("id"),
        email = getString("email"),
        passwordHash = getString("password_hash"),
        createdAt = getLong("created_at"),
    )
}
