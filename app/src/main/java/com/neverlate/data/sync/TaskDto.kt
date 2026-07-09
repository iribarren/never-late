package com.neverlate.data.sync

import kotlinx.serialization.Serializable

/**
 * The wire shape for a task, per `docs/api/contract.md` §4 — deliberately different from the
 * Room [com.neverlate.data.tasks.Task] entity, continuing feature 10's DTO-≠-domain-model split
 * ([com.neverlate.data.articles.ArticleDto]). The differences all carry meaning:
 * - [id] is the **server's** id (maps to [com.neverlate.data.tasks.Task.serverId]); the client's
 *   own autoincrement `Task.id` never crosses the wire.
 * - [clientRef] only matters for creates (idempotency, see the contract) but is present on every
 *   DTO the server sends back.
 * - [deleted] is the tombstone flag pulls use to propagate a delete.
 * - There is no timer state — [com.neverlate.data.tasks.Task.timerEndsAt]/`remainingMillis` are
 *   local-only (see that class's KDoc).
 * - [completedAt] (feature 04c) is client-provided, exactly like [deadline] — the server is
 *   authoritative only over [id]/[updatedAt], never over completion.
 *
 * See [TaskMapping.kt][toNewLocalTask] for how a [TaskDto] becomes a local [com.neverlate.data.tasks.Task].
 */
@Serializable
data class TaskDto(
    val id: Long,
    val clientRef: String,
    val title: String,
    val estimatedDurationMillis: Long? = null,
    val deadline: Long? = null,
    /** Epoch millis, `null` while the task is not done (contract.md §4). */
    val completedAt: Long? = null,
    val deleted: Boolean = false,
    val updatedAt: Long,
    val createdAt: Long = 0L,
)

/** `GET /tasks?since=` response envelope — the tasks changed since the cursor, plus the server's
 *  own clock at response time (the next cursor to persist). */
@Serializable
data class TasksPullResponse(
    val tasks: List<TaskDto>,
    val serverTime: Long,
)

/** `POST /tasks` request body. */
@Serializable
data class CreateTaskRequest(
    val clientRef: String,
    val title: String,
    val estimatedDurationMillis: Long? = null,
    val deadline: Long? = null,
    val completedAt: Long? = null,
    val updatedAt: Long,
)

/** `PATCH /tasks/{id}` request body. This client always sends every updatable field (rather than
 *  a true partial patch) — simpler to reason about, and the contract explicitly allows "any
 *  updatable subset", so a full body is a valid subset too. Always sending [completedAt] this way
 *  also sidesteps the server's omitted-vs-present-null distinction (contract.md §3): this client
 *  never omits it, so an explicit `null` unambiguously means "clear/un-complete". */
@Serializable
data class UpdateTaskRequest(
    val title: String,
    val estimatedDurationMillis: Long? = null,
    val deadline: Long? = null,
    val completedAt: Long? = null,
    val updatedAt: Long,
)
