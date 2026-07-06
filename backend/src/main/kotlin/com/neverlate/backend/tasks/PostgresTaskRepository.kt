package com.neverlate.backend.tasks

import java.sql.ResultSet
import javax.sql.DataSource

class PostgresTaskRepository(private val dataSource: DataSource) : TaskRepository {

    override fun findByClientRef(userId: Long, clientRef: String): Task? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "$SELECT_COLUMNS FROM tasks WHERE user_id = ? AND client_ref = ?",
            ).use { stmt ->
                stmt.setLong(1, userId)
                stmt.setString(2, clientRef)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.toTask() else null }
            }
        }

    override fun findById(userId: Long, id: Long): Task? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "$SELECT_COLUMNS FROM tasks WHERE user_id = ? AND id = ?",
            ).use { stmt ->
                stmt.setLong(1, userId)
                stmt.setLong(2, id)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.toTask() else null }
            }
        }

    override fun create(
        userId: Long,
        clientRef: String,
        title: String,
        estimatedDurationMillis: Long?,
        deadline: Long?,
        now: Long,
    ): Task {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO tasks
                    (user_id, client_ref, title, estimated_duration_ms, deadline, updated_at, deleted, created_at)
                VALUES (?, ?, ?, ?, ?, ?, FALSE, ?)
                RETURNING id
                """.trimIndent(),
            ).use { stmt ->
                stmt.setLong(1, userId)
                stmt.setString(2, clientRef)
                stmt.setString(3, title)
                stmt.setNullableLong(4, estimatedDurationMillis)
                stmt.setNullableLong(5, deadline)
                stmt.setLong(6, now)
                stmt.setLong(7, now)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return Task(
                        id = rs.getLong("id"),
                        userId = userId,
                        clientRef = clientRef,
                        title = title,
                        estimatedDurationMillis = estimatedDurationMillis,
                        deadline = deadline,
                        deleted = false,
                        updatedAt = now,
                        createdAt = now,
                    )
                }
            }
        }
    }

    override fun update(
        userId: Long,
        id: Long,
        title: String,
        estimatedDurationMillis: Long?,
        deadline: Long?,
        now: Long,
    ): Task? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE tasks
                SET title = ?, estimated_duration_ms = ?, deadline = ?, updated_at = ?
                WHERE user_id = ? AND id = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, title)
                stmt.setNullableLong(2, estimatedDurationMillis)
                stmt.setNullableLong(3, deadline)
                stmt.setLong(4, now)
                stmt.setLong(5, userId)
                stmt.setLong(6, id)
                val rows = stmt.executeUpdate()
                if (rows == 0) null else findById(userId, id)
            }
        }

    override fun softDelete(userId: Long, id: Long, now: Long): Task? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE tasks SET deleted = TRUE, updated_at = ? WHERE user_id = ? AND id = ?",
            ).use { stmt ->
                stmt.setLong(1, now)
                stmt.setLong(2, userId)
                stmt.setLong(3, id)
                val rows = stmt.executeUpdate()
                if (rows == 0) null else findById(userId, id)
            }
        }

    override fun findChangedSince(userId: Long, since: Long): List<Task> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "$SELECT_COLUMNS FROM tasks WHERE user_id = ? AND updated_at >= ? ORDER BY updated_at ASC",
            ).use { stmt ->
                stmt.setLong(1, userId)
                stmt.setLong(2, since)
                stmt.executeQuery().use { rs ->
                    val results = mutableListOf<Task>()
                    while (rs.next()) results.add(rs.toTask())
                    results
                }
            }
        }

    private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) setNull(index, java.sql.Types.BIGINT) else setLong(index, value)
    }

    private fun ResultSet.toTask() = Task(
        id = getLong("id"),
        userId = getLong("user_id"),
        clientRef = getString("client_ref"),
        title = getString("title"),
        estimatedDurationMillis = getLong("estimated_duration_ms").let { if (wasNull()) null else it },
        deadline = getLong("deadline").let { if (wasNull()) null else it },
        deleted = getBoolean("deleted"),
        updatedAt = getLong("updated_at"),
        createdAt = getLong("created_at"),
    )

    private companion object {
        const val SELECT_COLUMNS =
            "SELECT id, user_id, client_ref, title, estimated_duration_ms, deadline, deleted, updated_at, created_at"
    }
}
