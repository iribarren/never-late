package com.neverlate.data.sync

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Typed description of the task endpoints in `docs/api/contract.md` §3 — same Retrofit pattern
 * [com.neverlate.data.articles.ArticlesApi] introduced in feature 10, extended to writes. Every
 * call here requires `Authorization: Bearer <token>`, attached by
 * [com.neverlate.data.sync.AuthInterceptor] (configured in [TasksNetwork]), never by this
 * interface itself.
 */
interface TasksApi {
    /** The **pull** half of sync: every task changed at/after [since] (epoch millis), including tombstones. */
    @GET("tasks")
    suspend fun getTasks(@Query("since") since: Long): TasksPullResponse

    /** Idempotent by [CreateTaskRequest.clientRef] — a retried create returns the existing task, not a duplicate. */
    @POST("tasks")
    suspend fun createTask(@Body request: CreateTaskRequest): TaskDto

    /** [id] is the **server** id ([com.neverlate.data.tasks.Task.serverId]), not the local one. */
    @PATCH("tasks/{id}")
    suspend fun updateTask(@Path("id") id: Long, @Body request: UpdateTaskRequest): TaskDto

    /** Soft delete (tombstone) — see the contract's §3. */
    @DELETE("tasks/{id}")
    suspend fun deleteTask(@Path("id") id: Long): TaskDto
}
