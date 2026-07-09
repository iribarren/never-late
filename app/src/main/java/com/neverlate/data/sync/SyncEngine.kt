package com.neverlate.data.sync

import androidx.room.withTransaction
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.auth.TokenStorage
import com.neverlate.data.tasks.NeverLateDatabase
import com.neverlate.data.tasks.SyncState
import com.neverlate.domain.sync.PulledTaskAction
import com.neverlate.domain.sync.reconcilePulledTask
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/** A poison-pill outbox row (the server keeps rejecting it, e.g. a validation error) is dropped
 *  after this many failed attempts, rather than blocking every other queued change behind it forever. */
private const val MAX_PUSH_RETRIES = 5

/**
 * Coordinates **push** (replay the outbox) and **pull** (`GET /tasks?since=`) against [api] — the
 * `SyncEngine` the feature spec's *Sync Model* describes. [com.neverlate.data.sync.OutboxTaskRepository]
 * is the only [com.neverlate.data.tasks.TaskRepository] that talks to this class directly (see
 * `TaskRepository.refreshFromServer`/`observeSyncStatus`); nothing else in the app does.
 *
 * Callers never see an exception from [syncNow]/[schedulePush] — every failure is caught and
 * turned into a [SyncStatus] instead, since a sync attempt failing (e.g. no connectivity) is a
 * completely normal, expected outcome in an offline-first app, not an error condition a caller
 * needs to handle specially.
 */
class SyncEngine(
    private val api: TasksApi,
    private val database: NeverLateDatabase,
    private val preferences: UserPreferencesRepository,
    private val tokenStorage: TokenStorage,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val taskDao = database.taskDao()
    private val outboxDao = database.outboxDao()

    // Serializes overlapping sync attempts (e.g. a pull-to-refresh while the WorkManager job also
    // happens to be running) so two pushes/pulls never race against the same outbox/cursor.
    private val mutex = Mutex()

    // Used only by schedulePush's fire-and-forget trigger — see its KDoc.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * Best-effort, fire-and-forget sync — the trigger [OutboxTaskRepository] uses right after
     * every local mutation (US-4's "push inmediato best-effort si hay red"). Runs on [scope]
     * rather than the caller's coroutine, so a save/delete returns immediately instead of waiting
     * on a network round-trip; [status] and the next scheduled [SyncWorker] run are what actually
     * surface/retry the outcome.
     */
    fun schedulePush() {
        scope.launch { syncNow() }
    }

    /**
     * Runs one push-then-pull cycle, updating [status] throughout. Safe to call from multiple
     * places concurrently (pull-to-refresh, [schedulePush], [SyncWorker]) — [mutex] makes the
     * second caller simply wait for the first cycle to finish rather than interleave with it.
     */
    suspend fun syncNow(): SyncStatus = mutex.withLock {
        if (tokenStorage.getAccessToken() == null) {
            // Logged out: nothing to sync, and calling the API would only earn a 401. Reachable
            // from a periodic SyncWorker run that fires after a logout but before WorkManager
            // gets around to cancelling it.
            return@withLock SyncStatus.Idle
        }

        _status.value = SyncStatus.Syncing
        val result = try {
            push()
            pull()
            SyncStatus.UpToDate
        } catch (error: IOException) {
            SyncStatus.Offline
        } catch (error: HttpException) {
            SyncStatus.Error
        }
        _status.value = result
        result
    }

    /** Replays every queued [OutboxEntity], oldest first, against [api]. */
    private suspend fun push() {
        outboxDao.getAll().forEach { row -> pushOne(row) }
    }

    private suspend fun pushOne(row: OutboxEntity) {
        val task = taskDao.getByIdIncludingDeleted(row.taskLocalId)
        if (task == null) {
            // The local row is already gone (e.g. deleted twice in a row before this ever ran) —
            // nothing left to push.
            outboxDao.deleteForTask(row.taskLocalId)
            return
        }

        try {
            when (row.operation) {
                OutboxOperation.CREATE -> {
                    val dto = api.createTask(task.toCreateRequest(row.clientRef))
                    database.withTransaction {
                        taskDao.update(task.copy(serverId = dto.id, updatedAt = dto.updatedAt, syncState = SyncState.SYNCED))
                        outboxDao.deleteForTask(row.taskLocalId)
                    }
                }

                OutboxOperation.UPDATE -> {
                    val serverId = task.serverId
                    if (serverId == null) {
                        // Should not happen (an UPDATE row implies a prior successful create), but
                        // there is nothing sane to PATCH without a server id — drop it rather than
                        // loop forever.
                        outboxDao.deleteForTask(row.taskLocalId)
                    } else {
                        // The server enforces last-write-wins on PATCH (see the API contract) and
                        // always returns the *resulting* row — trust it completely, even if it
                        // decided to keep its own newer copy instead of applying this update.
                        val dto = api.updateTask(serverId, task.toUpdateRequest())
                        database.withTransaction {
                            taskDao.update(
                                task.copy(
                                    title = dto.title,
                                    estimatedDurationMillis = dto.estimatedDurationMillis,
                                    deadline = dto.deadline,
                                    completedAt = dto.completedAt,
                                    updatedAt = dto.updatedAt,
                                    syncState = SyncState.SYNCED,
                                ),
                            )
                            outboxDao.deleteForTask(row.taskLocalId)
                        }
                    }
                }

                OutboxOperation.DELETE -> {
                    val serverId = task.serverId
                    if (serverId != null) api.deleteTask(serverId)
                    database.withTransaction {
                        taskDao.deleteById(row.taskLocalId)
                        outboxDao.deleteForTask(row.taskLocalId)
                    }
                }
            }
        } catch (error: IOException) {
            // Offline/transient — stop pushing further rows (preserves outbox order) and let
            // syncNow's catch report Offline; the periodic SyncWorker retries the whole queue
            // once WorkManager's NetworkType.CONNECTED constraint is satisfied again.
            throw error
        } catch (error: HttpException) {
            if (row.retryCount + 1 >= MAX_PUSH_RETRIES) {
                outboxDao.deleteForTask(row.taskLocalId)
            } else {
                outboxDao.incrementRetry(row.taskLocalId)
            }
        }
    }

    /** Pulls every task changed since the persisted cursor and reconciles it into the local cache. */
    private suspend fun pull() {
        val since = preferences.userPreferences.first().syncCursor
        val response = api.getTasks(since)

        database.withTransaction {
            response.tasks.forEach { dto ->
                val local = taskDao.getByServerId(dto.id)
                when (val action = reconcilePulledTask(local, dto)) {
                    is PulledTaskAction.Upsert -> {
                        val task = action.task
                        if (task.id == 0L) taskDao.insert(task) else taskDao.update(task)
                        // The server's copy won over whatever was locally pending (or there was
                        // nothing pending) — either way, any queued outbox row for it is moot now.
                        if (local != null) outboxDao.deleteForTask(local.id)
                    }

                    is PulledTaskAction.Delete -> {
                        taskDao.deleteById(action.localId)
                        outboxDao.deleteForTask(action.localId)
                    }

                    PulledTaskAction.Ignore -> Unit
                }
            }
        }

        preferences.saveSyncCursor(response.serverTime)
    }
}
