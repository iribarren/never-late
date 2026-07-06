package com.neverlate.backend.tasks

import com.neverlate.backend.common.NotFoundException
import com.neverlate.backend.common.PatchValue
import com.neverlate.backend.common.ValidationException
import com.neverlate.backend.common.orElse

/**
 * Business logic for the `/tasks*` endpoints, scoped to a single authenticated `userId` on every
 * call (the route layer extracts that id from the verified JWT — see plugins/Security.kt — and
 * passes it in; nothing here ever reads a user id from the request body). Depends only on
 * [TaskRepository], so it's tested against [InMemoryTaskRepository] with no real DB.
 */
class TaskService(private val tasks: TaskRepository) {

    /** `POST /tasks` — idempotent by `clientRef` (contract.md §3): replaying the same
     *  `clientRef` returns the already-created row instead of inserting a duplicate. Returns
     *  the resulting [TaskDto] plus whether it was freshly created (so the route can pick
     *  201 vs 200). */
    fun create(
        userId: Long,
        clientRef: String,
        title: String,
        estimatedDurationMillis: Long?,
        deadline: Long?,
    ): CreateResult {
        tasks.findByClientRef(userId, clientRef)?.let { existing ->
            return CreateResult(task = existing.toDto(), created = false)
        }
        validate(title = title, estimatedDurationMillis = estimatedDurationMillis, deadline = deadline)
        val task = tasks.create(
            userId = userId,
            clientRef = clientRef,
            title = title,
            estimatedDurationMillis = estimatedDurationMillis,
            deadline = deadline,
            now = System.currentTimeMillis(),
        )
        return CreateResult(task = task.toDto(), created = true)
    }

    /** `PATCH /tasks/{id}` — last-write-wins by `updatedAt` (contract.md §3): if [clientUpdatedAt]
     *  (the edit time the client claims) is older than the row's currently stored `updatedAt`,
     *  the incoming change is discarded and the current server row is returned unchanged. */
    fun update(
        userId: Long,
        id: Long,
        title: String?,
        estimatedDurationMillis: PatchValue<Long>,
        deadline: PatchValue<Long>,
        clientUpdatedAt: Long,
    ): TaskDto {
        val current = tasks.findById(userId, id) ?: throw NotFoundException("No such task")

        if (clientUpdatedAt < current.updatedAt) {
            // Stale write — the server's copy is newer. Keep it; the client will reconcile this
            // on its next pull (contract.md §3, §5).
            return current.toDto()
        }

        val mergedTitle = title ?: current.title
        val mergedDuration = estimatedDurationMillis.orElse(current.estimatedDurationMillis)
        val mergedDeadline = deadline.orElse(current.deadline)
        validate(title = mergedTitle, estimatedDurationMillis = mergedDuration, deadline = mergedDeadline)

        val updated = tasks.update(
            userId = userId,
            id = id,
            title = mergedTitle,
            estimatedDurationMillis = mergedDuration,
            deadline = mergedDeadline,
            now = System.currentTimeMillis(),
        ) ?: throw NotFoundException("No such task")
        return updated.toDto()
    }

    /** `DELETE /tasks/{id}` — soft delete/tombstone. Unconditional: delete always wins over a
     *  concurrent edit (contract.md §5), so there's no `updatedAt` comparison here. */
    fun delete(userId: Long, id: Long): TaskDto {
        val tombstoned = tasks.softDelete(userId, id, now = System.currentTimeMillis())
            ?: throw NotFoundException("No such task")
        return tombstoned.toDto()
    }

    /** `GET /tasks?since=` — the pull half of sync (contract.md §3). */
    fun listChangedSince(userId: Long, since: Long): TasksResponse {
        val now = System.currentTimeMillis()
        val changed = tasks.findChangedSince(userId, since).map { it.toDto() }
        return TasksResponse(tasks = changed, serverTime = now)
    }

    private fun validate(title: String, estimatedDurationMillis: Long?, deadline: Long?) {
        if (title.isBlank()) throw ValidationException("title must not be blank")
        if (estimatedDurationMillis == null && deadline == null) {
            throw ValidationException("at least one of estimatedDurationMillis or deadline is required")
        }
    }

    data class CreateResult(val task: TaskDto, val created: Boolean)
}
