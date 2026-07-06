package com.neverlate.domain.sync

import com.neverlate.data.sync.TaskDto
import com.neverlate.data.sync.toExistingLocalTask
import com.neverlate.data.sync.toNewLocalTask
import com.neverlate.data.tasks.SyncState
import com.neverlate.data.tasks.Task

/**
 * Pure, Android-free reconciliation logic for feature 11's **pull** (see
 * `docs/specs/2026-07-06-remote-db-sync.md`'s *Sync Model*) — the sync counterpart of
 * `domain/tasks/ReminderPlanning.kt`: everything here takes plain values (a possibly-null local
 * [Task], a pulled [TaskDto]) and returns a plain value, so [com.neverlate.data.sync.SyncEngine]
 * never needs an emulator to have this decision unit-tested; a plain JVM test does it.
 *
 * The one rule this function encodes, per the API contract and spec (§5 / *Resolución de
 * conflictos*):
 * - **Delete wins over any concurrent edit**, from either side: a tombstone pulled from the
 *   server always removes the local row, even if that row has a pending local edit; and a
 *   locally-pending delete is never overwritten by a pulled edit.
 * - Otherwise, **last-write-wins by `updatedAt`**: a pulled DTO only overwrites a locally-pending
 *   edit if the DTO's `updatedAt` is strictly newer; a row with no pending local change
 *   ([SyncState.SYNCED]) always takes the server's copy (there is nothing local to protect).
 */
sealed interface PulledTaskAction {
    /** Insert (if [Task.id] is `0`) or overwrite the local row with [task]. */
    data class Upsert(val task: Task) : PulledTaskAction

    /** Hard-delete the local row [localId] — the pulled DTO (or the local outbox) tombstoned it. */
    data class Delete(val localId: Long) : PulledTaskAction

    /** Nothing to do: the local, still-unpushed change wins over this pulled DTO. */
    data object Ignore : PulledTaskAction
}

/**
 * Decides what a single pulled [dto] should do to the [local] row it corresponds to (matched by
 * [Task.serverId] before this function is ever called — see [com.neverlate.data.sync.SyncEngine.pull]).
 * [local] is null when this device has never seen this server task before.
 */
fun reconcilePulledTask(local: Task?, dto: TaskDto): PulledTaskAction {
    if (local == null) {
        // A tombstone for a task this device never had locally: there is nothing to delete.
        return if (dto.deleted) PulledTaskAction.Ignore else PulledTaskAction.Upsert(dto.toNewLocalTask())
    }

    val hasPendingDelete = local.syncState == SyncState.PENDING_DELETE
    val hasPendingEdit = local.syncState == SyncState.PENDING_CREATE || local.syncState == SyncState.PENDING_UPDATE

    return when {
        // Delete wins, unconditionally, over any concurrent edit — from the server's side...
        dto.deleted -> PulledTaskAction.Delete(local.id)
        // ...or from this device's side: our own pending delete is not undone by a pulled edit.
        hasPendingDelete -> PulledTaskAction.Ignore
        // Last-write-wins: only overwrite a pending local edit if the server's copy is newer.
        hasPendingEdit -> if (dto.updatedAt > local.updatedAt) {
            PulledTaskAction.Upsert(dto.toExistingLocalTask(local.id))
        } else {
            PulledTaskAction.Ignore
        }
        // No pending local change: the server's copy is always authoritative.
        else -> PulledTaskAction.Upsert(dto.toExistingLocalTask(local.id))
    }
}
