package com.neverlate.data.sync

import com.neverlate.data.tasks.SyncState
import com.neverlate.data.tasks.Task

/**
 * Pure `Task <-> TaskDto` mapping — this feature's version of [com.neverlate.data.articles.ArticleDto.toDomain],
 * kept as small top-level functions (no Android, no I/O) so [com.neverlate.domain.sync.reconcilePulledTask]
 * and [com.neverlate.data.sync.SyncEngine] can both use them, and so they are trivial to unit-test
 * on the plain JVM.
 */

/** Builds the `POST /tasks` body for a never-yet-synced [Task], stamping it with [clientRef]. */
fun Task.toCreateRequest(clientRef: String): CreateTaskRequest = CreateTaskRequest(
    clientRef = clientRef,
    title = title,
    estimatedDurationMillis = estimatedDurationMillis,
    deadline = deadline,
    updatedAt = updatedAt,
)

/** Builds the `PATCH /tasks/{serverId}` body for an already-synced [Task]. */
fun Task.toUpdateRequest(): UpdateTaskRequest = UpdateTaskRequest(
    title = title,
    estimatedDurationMillis = estimatedDurationMillis,
    deadline = deadline,
    updatedAt = updatedAt,
)

/**
 * Maps a pulled [TaskDto] to a brand-new local [Task] row (`id = 0`, so Room will assign one on
 * insert). Always [SyncState.SYNCED] and never a tombstone: [com.neverlate.domain.sync.reconcilePulledTask]
 * only calls this for a DTO that is not itself deleted (see its KDoc).
 */
fun TaskDto.toNewLocalTask(): Task = Task(
    title = title,
    estimatedDurationMillis = estimatedDurationMillis,
    deadline = deadline,
    serverId = id,
    updatedAt = updatedAt,
    syncState = SyncState.SYNCED,
    deleted = false,
)

/** Same mapping as [toNewLocalTask], but overwriting the row already at [localId] instead of inserting a new one. */
fun TaskDto.toExistingLocalTask(localId: Long): Task = toNewLocalTask().copy(id = localId)
