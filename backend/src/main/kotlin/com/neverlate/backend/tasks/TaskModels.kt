package com.neverlate.backend.tasks

import kotlinx.serialization.Serializable

/** Server-side task row. Deliberately distinct from [TaskDto] (the wire shape) — same
 *  DTO-≠-entity teaching point the Android client already makes for `ArticleDto`/`Article`
 *  (see CLAUDE.md feature 10): this type is free to gain server-only concerns later (e.g. an
 *  internal audit column) without that leaking onto the wire. */
data class Task(
    val id: Long,
    val userId: Long,
    val clientRef: String,
    val title: String,
    val estimatedDurationMillis: Long?,
    val deadline: Long?,
    val deleted: Boolean,
    val updatedAt: Long,
    val createdAt: Long,
)

fun Task.toDto() = TaskDto(
    id = id,
    clientRef = clientRef,
    title = title,
    estimatedDurationMillis = estimatedDurationMillis,
    deadline = deadline,
    deleted = deleted,
    updatedAt = updatedAt,
    createdAt = createdAt,
)

/** The wire shape (contract.md §4). */
@Serializable
data class TaskDto(
    val id: Long,
    val clientRef: String,
    val title: String,
    val estimatedDurationMillis: Long? = null,
    val deadline: Long? = null,
    val deleted: Boolean = false,
    val updatedAt: Long,
    val createdAt: Long,
)

/** `POST /tasks` request body (contract.md §3). `updatedAt` is accepted for API-shape symmetry
 *  with the client's local row, but the server is authoritative: the stored `updatedAt` is
 *  always set from the server clock (contract.md: "The server is the authority on a task's
 *  updatedAt and id"), not copied from this field. */
@Serializable
data class CreateTaskRequest(
    val clientRef: String,
    val title: String,
    val estimatedDurationMillis: Long? = null,
    val deadline: Long? = null,
    val updatedAt: Long? = null,
)

/** `PATCH /tasks/{id}` request body shape, for documentation/OpenAPI purposes (contract.md §3).
 *  The actual route does NOT decode into this type directly: `estimatedDurationMillis` and
 *  `deadline` need to distinguish "key omitted" (leave unchanged) from "key present with value
 *  `null`" (clear the field), which a plain `@Serializable` data class can't express — see
 *  [com.neverlate.backend.common.PatchValue] and how `tasks/TaskRoutes.kt` reads the raw JSON
 *  object instead. `title` doesn't need this treatment: a task's title is never allowed to be
 *  blank/absent (domain rule), so `title: null` unambiguously means "unchanged". */
@Serializable
data class UpdateTaskRequest(
    val title: String? = null,
    val estimatedDurationMillis: Long? = null,
    val deadline: Long? = null,
    val updatedAt: Long,
)

/** `GET /tasks?since=` response (contract.md §3). */
@Serializable
data class TasksResponse(val tasks: List<TaskDto>, val serverTime: Long)
