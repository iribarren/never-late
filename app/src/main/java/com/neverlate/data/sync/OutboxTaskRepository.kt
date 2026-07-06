package com.neverlate.data.sync

import androidx.room.withTransaction
import com.neverlate.data.tasks.NeverLateDatabase
import com.neverlate.data.tasks.SyncState
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Decorates [delegate] (the real, Room-backed [TaskRepository] — see
 * [com.neverlate.data.tasks.RoomTaskRepository]) so that every write which changes a task's
 * durable fields also stamps its sync metadata and enqueues an outbox row, **in the same
 * transaction** as the task row itself (US-3 of the feature spec: a crash between the two must
 * never lose the intention to sync). This is the client-side "heart" of feature 11 — the
 * decorator equivalent of [com.neverlate.ui.notification.ReminderSchedulingRepository] /
 * [com.neverlate.ui.widget.TaskSurfacesRefreshingRepository], composed the same way in
 * `MainActivity`.
 *
 * [startTimer]/[pauseTimer] are forwarded to [delegate] untouched: per the API contract, timer
 * state is local-only and never synced (see [Task]'s KDoc), so those two methods have nothing to
 * do with the outbox at all.
 */
class OutboxTaskRepository(
    private val database: NeverLateDatabase,
    private val delegate: TaskRepository,
    private val syncEngine: SyncEngine,
    private val now: () -> Long = System::currentTimeMillis,
) : TaskRepository {

    private val taskDao = database.taskDao()
    private val outboxDao = database.outboxDao()

    override fun observeTasks(): Flow<List<Task>> = delegate.observeTasks()

    override fun observeTask(id: Long): Flow<Task?> = delegate.observeTask(id)

    override suspend fun startTimer(id: Long) = delegate.startTimer(id)

    override suspend fun pauseTimer(id: Long) = delegate.pauseTimer(id)

    override suspend fun saveTask(task: Task): Long {
        val id = database.withTransaction {
            // Whether Room needs an insert or an update is purely about the *local* row (has it
            // ever been saved before, i.e. does it have a real id yet). Whether the outbox should
            // queue a CREATE or an UPDATE is a different question — whether the *server* has ever
            // seen this task — and must be answered from serverId, not from the local id: a task
            // saved once (id assigned) and then edited again before its first push ever completes
            // still has serverId == null, and must still queue as a CREATE. Conflating the two
            // (as an earlier version of this method did, keying both off task.id == 0L) meant a
            // second edit before the first sync got queued as an UPDATE with no serverId, which
            // SyncEngine.pushOne's UPDATE branch then silently drops — permanently losing the task.
            val isNewLocalRow = task.id == 0L
            val neverSyncedToServer = task.serverId == null
            val stamped = task.copy(
                updatedAt = now(),
                syncState = if (neverSyncedToServer) SyncState.PENDING_CREATE else SyncState.PENDING_UPDATE,
                deleted = false,
            )
            val savedId = if (isNewLocalRow) taskDao.insert(stamped) else {
                taskDao.update(stamped)
                stamped.id
            }
            outboxDao.enqueue(
                OutboxEntity(
                    taskLocalId = savedId,
                    operation = if (neverSyncedToServer) OutboxOperation.CREATE else OutboxOperation.UPDATE,
                    // Reuse the clientRef of any still-pending row for this task (e.g. edited
                    // again before its first push ever completed) — a create's idempotency token
                    // must stay stable across retries, not be regenerated on every edit.
                    clientRef = outboxDao.getForTask(savedId)?.clientRef ?: UUID.randomUUID().toString(),
                    enqueuedAt = now(),
                ),
            )
            savedId
        }
        syncEngine.schedulePush()
        return id
    }

    override suspend fun deleteTask(id: Long) {
        database.withTransaction {
            val existing = taskDao.getByIdIncludingDeleted(id) ?: return@withTransaction
            if (existing.serverId == null) {
                // Never reached the server (still a pending create) — there is nothing to tell
                // it about a delete; just remove the local row and whatever was queued for it.
                taskDao.deleteById(id)
                outboxDao.deleteForTask(id)
            } else {
                taskDao.update(existing.copy(deleted = true, syncState = SyncState.PENDING_DELETE, updatedAt = now()))
                outboxDao.enqueue(
                    OutboxEntity(
                        taskLocalId = id,
                        operation = OutboxOperation.DELETE,
                        clientRef = outboxDao.getForTask(id)?.clientRef ?: UUID.randomUUID().toString(),
                        enqueuedAt = now(),
                    ),
                )
            }
        }
        syncEngine.schedulePush()
    }

    override suspend fun refreshFromServer() {
        syncEngine.syncNow()
    }

    override fun observeSyncStatus(): Flow<SyncStatus> = syncEngine.status
}
