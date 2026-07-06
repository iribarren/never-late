package com.neverlate.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room-generated access to the `task_outbox` table — see [OutboxEntity] for why it holds at most
 * one row per task.
 */
@Dao
interface OutboxDao {
    /** Queues (or replaces) [entry] as the latest pending change for its task. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(entry: OutboxEntity)

    /** Every pending change, oldest-queued first — the order [SyncEngine.push] replays them in. */
    @Query("SELECT * FROM task_outbox ORDER BY enqueuedAt ASC")
    suspend fun getAll(): List<OutboxEntity>

    @Query("SELECT * FROM task_outbox WHERE taskLocalId = :taskLocalId LIMIT 1")
    suspend fun getForTask(taskLocalId: Long): OutboxEntity?

    /** Removes the pending change for a task, once it has been pushed (or is no longer needed). */
    @Query("DELETE FROM task_outbox WHERE taskLocalId = :taskLocalId")
    suspend fun deleteForTask(taskLocalId: Long)

    @Query("UPDATE task_outbox SET retryCount = retryCount + 1 WHERE taskLocalId = :taskLocalId")
    suspend fun incrementRetry(taskLocalId: Long)

    /** Wipes the outbox — used on logout, alongside [com.neverlate.data.tasks.TaskDao.clearAll]. */
    @Query("DELETE FROM task_outbox")
    suspend fun clearAll()
}
