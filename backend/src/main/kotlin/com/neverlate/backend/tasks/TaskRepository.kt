package com.neverlate.backend.tasks

import com.neverlate.backend.common.PatchValue

/**
 * The repository seam for tasks — same pattern as [com.neverlate.backend.auth.UserRepository]
 * and the client's `TaskRepository` (CLAUDE.md). Every method is scoped to a `userId`: nothing
 * in [TaskService] (or above it) can accidentally query across users, because the interface
 * simply has no method that doesn't take one (contract.md §1.2 — "a client is untrusted; it can
 * never read or write another user's tasks").
 */
interface TaskRepository {
    /** For idempotent creates: if [clientRef] was already used by this user, returns that row
     *  instead of creating a duplicate. */
    fun findByClientRef(userId: Long, clientRef: String): Task?

    fun findById(userId: Long, id: Long): Task?

    fun create(
        userId: Long,
        clientRef: String,
        title: String,
        estimatedDurationMillis: Long?,
        deadline: Long?,
        completedAt: Long?,
        priority: String,
        now: Long,
    ): Task

    /** Applies an already-validated, already-merged update and stamps `updatedAt = now`.
     *  Returns null if the row doesn't exist for this user (the caller is expected to have
     *  already checked existence and LWW ordering before calling this). */
    fun update(
        userId: Long,
        id: Long,
        title: String,
        estimatedDurationMillis: Long?,
        deadline: Long?,
        completedAt: Long?,
        priority: String,
        now: Long,
    ): Task?

    /** Soft-deletes (tombstones) a row: sets `deleted = true`, `updatedAt = now`. Returns null if
     *  the row doesn't exist for this user. */
    fun softDelete(userId: Long, id: Long, now: Long): Task?

    /** Every row (including tombstones) for [userId] with `updatedAt >= since` — the pull half
     *  of sync (contract.md §3, `GET /tasks?since=`). */
    fun findChangedSince(userId: Long, since: Long): List<Task>
}

/** Convenience used by [TaskService.update] to keep call sites readable; not part of the
 *  repository contract itself. */
data class TaskUpdateFields(
    val title: String?,
    val estimatedDurationMillis: PatchValue<Long>,
    val deadline: PatchValue<Long>,
    val completedAt: PatchValue<Long>,
    val priority: PatchValue<String>,
)
