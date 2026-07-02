package com.neverlate.data.tasks

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** [TaskRepository] backed by [NeverLateDatabase], via [TaskDao]. */
class RoomTaskRepository(private val dao: TaskDao) : TaskRepository {

    override fun observeTasks(): Flow<List<Task>> = dao.observeTasks()

    override fun observeTask(id: Long): Flow<Task?> = dao.observeTask(id)

    override suspend fun saveTask(task: Task) {
        // A fresh, never-saved Task keeps Room's default id (0); autoGenerate then assigns a
        // real one on insert. Any other id means a row already exists for it, so it is an update.
        if (task.id == 0L) dao.insert(task) else dao.update(task)
    }

    override suspend fun deleteTask(id: Long) {
        dao.deleteById(id)
    }

    override suspend fun startTimer(id: Long) {
        // `.first()` reads one value off the Flow and stops observing — the right tool here
        // because this is a one-shot "read the current row" rather than an ongoing subscription.
        val task = dao.observeTask(id).first() ?: return
        val now = System.currentTimeMillis()
        val remaining = computeRemainingMillis(task, now)
        dao.update(task.copy(timerEndsAt = now + remaining, remainingMillis = null))
    }

    override suspend fun pauseTimer(id: Long) {
        val task = dao.observeTask(id).first() ?: return
        val now = System.currentTimeMillis()
        val remaining = computeRemainingMillis(task, now)
        dao.update(task.copy(timerEndsAt = null, remainingMillis = remaining))
    }
}
