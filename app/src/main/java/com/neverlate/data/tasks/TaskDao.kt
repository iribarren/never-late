package com.neverlate.data.tasks

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room-generated access to the `tasks` table. `@Dao` interfaces need no implementation: KSP
 * (declared as this module's annotation processor in `app/build.gradle.kts`) generates one at
 * build time from these annotated method signatures.
 *
 * Writes are `suspend` functions (Room dispatches them off the main thread automatically);
 * reads that the UI needs to keep observing return [Flow] instead, so [observeTasks] and
 * [observeTask] re-emit every time the underlying table changes.
 *
 * Feature 11: [observeTasks]/[observeTask] filter out tombstoned rows (`deleted = 1`) — a task
 * pending a `DELETE` push, or one a pull just tombstoned, is not "pending" work the UI should
 * still show. The one-shot queries below (used by [com.neverlate.data.sync.SyncEngine] and
 * [com.neverlate.data.sync.OutboxTaskRepository], never by the UI) deliberately do **not** filter
 * it out, since sync needs to see — and eventually clean up — those rows too.
 */
@Dao
interface TaskDao {
    /** Inserts a brand-new [task] row and returns the id SQLite assigned it. */
    @Insert
    suspend fun insert(task: Task): Long

    /** Overwrites the row matching [task]'s id with [task]'s current field values. */
    @Update
    suspend fun update(task: Task)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM tasks WHERE deleted = 0 ORDER BY id DESC")
    fun observeTasks(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :id AND deleted = 0")
    fun observeTask(id: Long): Flow<Task?>

    /** One-shot read including tombstoned rows — for sync, which must still see a pending delete. */
    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getByIdIncludingDeleted(id: Long): Task?

    /** Looks up a row by the backend's id, used when reconciling a pulled [com.neverlate.data.sync.TaskDto]. */
    @Query("SELECT * FROM tasks WHERE serverId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: Long): Task?

    /** Wipes every local task — used on logout, since tasks are backend-owned (feature 11). */
    @Query("DELETE FROM tasks")
    suspend fun clearAll()
}
