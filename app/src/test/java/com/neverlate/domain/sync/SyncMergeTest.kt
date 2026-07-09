package com.neverlate.domain.sync

import com.neverlate.data.sync.TaskDto
import com.neverlate.data.tasks.SyncState
import com.neverlate.data.tasks.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive tests for [reconcilePulledTask] — the pure merge/conflict logic at the heart of
 * feature 11's pull (see `docs/specs/2026-07-06-remote-db-sync.md`'s *Sync Model* and the API
 * contract's §5). Every branch of the `when` in [reconcilePulledTask] gets its own test, following
 * the same "one test per decision branch" style as `ReminderPlanningTest`.
 */
class SyncMergeTest {

    private fun dto(
        id: Long = 42L,
        clientRef: String = "ref-42",
        title: String = "Pulled title",
        updatedAt: Long = 1_000L,
        deleted: Boolean = false,
        completedAt: Long? = null,
    ) = TaskDto(
        id = id,
        clientRef = clientRef,
        title = title,
        updatedAt = updatedAt,
        deleted = deleted,
        completedAt = completedAt,
    )

    private fun local(
        id: Long = 7L,
        serverId: Long? = 42L,
        title: String = "Local title",
        updatedAt: Long = 1_000L,
        syncState: SyncState = SyncState.SYNCED,
        deleted: Boolean = false,
        completedAt: Long? = null,
    ) = Task(
        id = id,
        title = title,
        serverId = serverId,
        updatedAt = updatedAt,
        syncState = syncState,
        deleted = deleted,
        completedAt = completedAt,
    )

    // No local row -------------------------------------------------------------------------------

    @Test
    fun `no local row and a non-deleted dto inserts a brand-new local task`() {
        val pulled = dto(id = 99L, title = "Nueva desde el servidor", updatedAt = 500L)

        val action = reconcilePulledTask(local = null, dto = pulled)

        assertTrue(action is PulledTaskAction.Upsert)
        val task = (action as PulledTaskAction.Upsert).task
        assertEquals(0L, task.id) // id = 0 so Room assigns a fresh local id on insert
        assertEquals(99L, task.serverId)
        assertEquals("Nueva desde el servidor", task.title)
        assertEquals(500L, task.updatedAt)
        assertEquals(SyncState.SYNCED, task.syncState)
        assertEquals(false, task.deleted)
    }

    @Test
    fun `no local row and a tombstoned dto is ignored - nothing to delete`() {
        val pulled = dto(deleted = true)

        val action = reconcilePulledTask(local = null, dto = pulled)

        assertEquals(PulledTaskAction.Ignore, action)
    }

    // Local synced (no pending change) ------------------------------------------------------------

    @Test
    fun `local synced row is always overwritten by the pulled copy, even with an older updatedAt`() {
        // No pending local change to protect: the server's copy always wins, regardless of clocks.
        val existing = local(syncState = SyncState.SYNCED, updatedAt = 5_000L)
        val pulled = dto(updatedAt = 1_000L, title = "Server version")

        val action = reconcilePulledTask(existing, pulled)

        assertTrue(action is PulledTaskAction.Upsert)
        val task = (action as PulledTaskAction.Upsert).task
        assertEquals(existing.id, task.id)
        assertEquals("Server version", task.title)
        assertEquals(1_000L, task.updatedAt)
    }

    @Test
    fun `local synced row is upserted with equal updatedAt`() {
        val existing = local(syncState = SyncState.SYNCED, updatedAt = 1_000L)
        val pulled = dto(updatedAt = 1_000L, title = "Server version")

        val action = reconcilePulledTask(existing, pulled)

        assertTrue(action is PulledTaskAction.Upsert)
        assertEquals("Server version", (action as PulledTaskAction.Upsert).task.title)
    }

    // Local pending update / create vs. a pulled edit (LWW) ---------------------------------------

    @Test
    fun `pending update loses to a strictly newer pulled edit - LWW`() {
        val existing = local(syncState = SyncState.PENDING_UPDATE, updatedAt = 1_000L, title = "Local edit")
        val pulled = dto(updatedAt = 2_000L, title = "Newer server edit")

        val action = reconcilePulledTask(existing, pulled)

        assertTrue(action is PulledTaskAction.Upsert)
        val task = (action as PulledTaskAction.Upsert).task
        assertEquals(existing.id, task.id) // overwrites the existing row, not a fresh insert
        assertEquals("Newer server edit", task.title)
        assertEquals(SyncState.SYNCED, task.syncState)
    }

    @Test
    fun `pending update wins over an older pulled edit - LWW`() {
        val existing = local(syncState = SyncState.PENDING_UPDATE, updatedAt = 2_000L, title = "Newer local edit")
        val pulled = dto(updatedAt = 1_000L, title = "Stale server copy")

        val action = reconcilePulledTask(existing, pulled)

        assertEquals(PulledTaskAction.Ignore, action)
    }

    @Test
    fun `pending update with equal updatedAt is a tie that the pulled copy does not win - local wins the tie`() {
        // reconcilePulledTask only overwrites on strictly-greater updatedAt (dto.updatedAt >
        // local.updatedAt), so an exact tie leaves the pending local edit untouched.
        val existing = local(syncState = SyncState.PENDING_UPDATE, updatedAt = 1_000L, title = "Local edit")
        val pulled = dto(updatedAt = 1_000L, title = "Server edit, same instant")

        val action = reconcilePulledTask(existing, pulled)

        assertEquals(PulledTaskAction.Ignore, action)
    }

    @Test
    fun `pending create loses to a strictly newer pulled edit - LWW`() {
        // A PENDING_CREATE with a serverId already assigned mid-flight is unusual but the rule is
        // identical to PENDING_UPDATE: treated as "a pending edit", not specially.
        val existing = local(syncState = SyncState.PENDING_CREATE, updatedAt = 1_000L, title = "Not-yet-pushed create")
        val pulled = dto(updatedAt = 2_000L, title = "Server already knows about it")

        val action = reconcilePulledTask(existing, pulled)

        assertTrue(action is PulledTaskAction.Upsert)
        assertEquals("Server already knows about it", (action as PulledTaskAction.Upsert).task.title)
    }

    @Test
    fun `pending create wins over an older pulled edit`() {
        val existing = local(syncState = SyncState.PENDING_CREATE, updatedAt = 2_000L)
        val pulled = dto(updatedAt = 1_000L)

        val action = reconcilePulledTask(existing, pulled)

        assertEquals(PulledTaskAction.Ignore, action)
    }

    // Delete wins, unconditionally --------------------------------------------------------------

    @Test
    fun `a pulled tombstone deletes a locally-synced row`() {
        val existing = local(syncState = SyncState.SYNCED)
        val pulled = dto(deleted = true)

        val action = reconcilePulledTask(existing, pulled)

        assertEquals(PulledTaskAction.Delete(existing.id), action)
    }

    @Test
    fun `a pulled tombstone deletes a row with a pending local edit - delete wins over edit`() {
        val existing = local(syncState = SyncState.PENDING_UPDATE, updatedAt = 9_999_999L) // "newer" local edit, irrelevant
        val pulled = dto(deleted = true, updatedAt = 1L) // even an "older" tombstone still wins

        val action = reconcilePulledTask(existing, pulled)

        assertEquals(PulledTaskAction.Delete(existing.id), action)
    }

    @Test
    fun `a pulled tombstone deletes a row already pending delete locally - idempotent`() {
        val existing = local(syncState = SyncState.PENDING_DELETE, deleted = true)
        val pulled = dto(deleted = true)

        val action = reconcilePulledTask(existing, pulled)

        assertEquals(PulledTaskAction.Delete(existing.id), action)
    }

    @Test
    fun `a local pending delete is not undone by a concurrent pulled edit - delete wins`() {
        val existing = local(syncState = SyncState.PENDING_DELETE, deleted = true, updatedAt = 1_000L)
        // The pulled edit is even strictly newer than the local delete, and still must not win.
        val pulled = dto(deleted = false, updatedAt = 5_000L, title = "Edited elsewhere")

        val action = reconcilePulledTask(existing, pulled)

        assertEquals(PulledTaskAction.Ignore, action)
    }

    // Completion (feature 04c) — reconcilePulledTask needs no logic change for this: completedAt
    // rides along inside TaskDto/Task like any other field, under the exact same rules above. ------

    @Test
    fun `pulling a dto with completedAt set upserts the completion onto the local task`() {
        val existing = local(syncState = SyncState.SYNCED, completedAt = null)
        val pulled = dto(updatedAt = 2_000L, completedAt = 5_000L)

        val action = reconcilePulledTask(existing, pulled)

        assertTrue(action is PulledTaskAction.Upsert)
        assertEquals(5_000L, (action as PulledTaskAction.Upsert).task.completedAt)
    }

    @Test
    fun `a newer-updatedAt pulled completion overwrites an older local edit - LWW`() {
        val existing = local(syncState = SyncState.PENDING_UPDATE, updatedAt = 1_000L, completedAt = null)
        val pulled = dto(updatedAt = 2_000L, completedAt = 2_000L)

        val action = reconcilePulledTask(existing, pulled)

        assertTrue(action is PulledTaskAction.Upsert)
        assertEquals(2_000L, (action as PulledTaskAction.Upsert).task.completedAt)
    }

    @Test
    fun `an older pulled completion is ignored in favor of a newer pending local edit - LWW`() {
        val existing = local(syncState = SyncState.PENDING_UPDATE, updatedAt = 5_000L, completedAt = 5_000L)
        val pulled = dto(updatedAt = 1_000L, completedAt = 1_000L)

        val action = reconcilePulledTask(existing, pulled)

        assertEquals(PulledTaskAction.Ignore, action)
    }

    @Test
    fun `a tombstone still wins over a pending local completion`() {
        val existing = local(syncState = SyncState.PENDING_UPDATE, updatedAt = 9_999_999L, completedAt = 9_999_999L)
        val pulled = dto(deleted = true, updatedAt = 1L) // even an "older" tombstone still wins

        val action = reconcilePulledTask(existing, pulled)

        assertEquals(PulledTaskAction.Delete(existing.id), action)
    }
}
