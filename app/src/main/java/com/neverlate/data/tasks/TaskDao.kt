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

    @Query("SELECT * FROM tasks ORDER BY id DESC")
    fun observeTasks(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeTask(id: Long): Flow<Task?>
}
