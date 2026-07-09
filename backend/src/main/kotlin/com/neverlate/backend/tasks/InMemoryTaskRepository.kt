package com.neverlate.backend.tasks

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Test fake for [TaskRepository] — see [com.neverlate.backend.auth.InMemoryUserRepository] for
 *  why: fast, hermetic tests with no Postgres/Docker involved. */
class InMemoryTaskRepository : TaskRepository {
    private val tasksById = ConcurrentHashMap<Long, Task>()
    private val nextId = AtomicLong(1)

    override fun findByClientRef(userId: Long, clientRef: String): Task? =
        tasksById.values.find { it.userId == userId && it.clientRef == clientRef }

    override fun findById(userId: Long, id: Long): Task? =
        tasksById[id]?.takeIf { it.userId == userId }

    override fun create(
        userId: Long,
        clientRef: String,
        title: String,
        estimatedDurationMillis: Long?,
        deadline: Long?,
        completedAt: Long?,
        now: Long,
    ): Task {
        val task = Task(
            id = nextId.getAndIncrement(),
            userId = userId,
            clientRef = clientRef,
            title = title,
            estimatedDurationMillis = estimatedDurationMillis,
            deadline = deadline,
            completedAt = completedAt,
            deleted = false,
            updatedAt = now,
            createdAt = now,
        )
        tasksById[task.id] = task
        return task
    }

    override fun update(
        userId: Long,
        id: Long,
        title: String,
        estimatedDurationMillis: Long?,
        deadline: Long?,
        completedAt: Long?,
        now: Long,
    ): Task? {
        val current = findById(userId, id) ?: return null
        val updated = current.copy(
            title = title,
            estimatedDurationMillis = estimatedDurationMillis,
            deadline = deadline,
            completedAt = completedAt,
            updatedAt = now,
        )
        tasksById[id] = updated
        return updated
    }

    override fun softDelete(userId: Long, id: Long, now: Long): Task? {
        val current = findById(userId, id) ?: return null
        val tombstoned = current.copy(deleted = true, updatedAt = now)
        tasksById[id] = tombstoned
        return tombstoned
    }

    override fun findChangedSince(userId: Long, since: Long): List<Task> =
        tasksById.values
            .filter { it.userId == userId && it.updatedAt >= since }
            .sortedBy { it.updatedAt }
}
