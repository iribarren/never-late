package com.neverlate.data.sync

import com.neverlate.data.tasks.NeverLateDatabase
import com.neverlate.data.tasks.SyncState
import com.neverlate.data.tasks.Task
import com.neverlate.ui.notification.FakeTaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [OutboxTaskRepository] — the decorator that stamps sync metadata and enqueues an outbox
 * row for every write, in the **same Room transaction** as the task row itself (US-3 of the
 * feature spec). Needs the real, Robolectric-backed in-memory [NeverLateDatabase] (see
 * [buildInMemoryTestDatabase]) rather than a hand-written DAO fake, because `database.withTransaction`
 * is a `RoomDatabase` extension with no fakeable seam of its own — the thing under test *is* that
 * it all lands in one real transaction.
 *
 * [delegate] only backs the pass-through reads/timer methods ([FakeTaskRepository], shared with
 * `com.neverlate.ui.notification.ReminderSchedulingRepositoryTest`) — `saveTask`/`deleteTask`
 * never call into it at all, they go straight through [database]'s own DAOs, so the assertions
 * below read the DB directly rather than the delegate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxTaskRepositoryTest {

    private val fixedNow = 9_000_000L

    private lateinit var database: NeverLateDatabase
    private lateinit var delegate: FakeTaskRepository
    private lateinit var syncEngine: SyncEngine
    private lateinit var repository: OutboxTaskRepository

    @Before
    fun setUp() {
        database = buildInMemoryTestDatabase()
        delegate = FakeTaskRepository()
        // No session -> SyncEngine.syncNow() (fired-and-forgotten by every save/delete) short-circuits
        // to Idle without ever touching the network, keeping these tests focused on the outbox write.
        val tokenStorage = FakeTokenStorage(token = null)
        val api = TasksNetwork.create(baseUrl = "http://localhost:8080/", tokenStorage = tokenStorage, onUnauthorized = {})
        syncEngine = SyncEngine(api, database, FakeUserPreferencesRepository(), tokenStorage, now = { fixedNow })
        repository = OutboxTaskRepository(database, delegate, syncEngine, now = { fixedNow })
    }

    @After
    fun tearDown() {
        database.close()
    }

    // saveTask: create -----------------------------------------------------------------------------

    @Test
    fun `saveTask on a brand-new task writes the task row and a CREATE outbox row in one write, returning the new id`() = runTest {
        val id = repository.saveTask(Task(id = 0, title = "Nueva tarea"))

        assertNotEquals(0L, id)
        val saved = database.taskDao().getByIdIncludingDeleted(id)!!
        assertEquals("Nueva tarea", saved.title)
        assertEquals(SyncState.PENDING_CREATE, saved.syncState)
        assertEquals(fixedNow, saved.updatedAt)
        assertEquals(false, saved.deleted)

        val outboxRow = database.outboxDao().getForTask(id)!!
        assertEquals(OutboxOperation.CREATE, outboxRow.operation)
        assertTrue(outboxRow.clientRef.isNotBlank())
    }

    // saveTask: update of an already-synced task ----------------------------------------------------

    @Test
    fun `saveTask on an already-synced task enqueues an UPDATE row, not a CREATE`() = runTest {
        val existingId = database.taskDao().insert(
            Task(title = "Ya sincronizada", serverId = 42L, updatedAt = 1L, syncState = SyncState.SYNCED),
        )
        val existing = database.taskDao().getByIdIncludingDeleted(existingId)!!

        repository.saveTask(existing.copy(title = "Editada"))

        val saved = database.taskDao().getByIdIncludingDeleted(existingId)!!
        assertEquals("Editada", saved.title)
        assertEquals(SyncState.PENDING_UPDATE, saved.syncState)
        assertEquals(fixedNow, saved.updatedAt)

        val outboxRow = database.outboxDao().getForTask(existingId)!!
        assertEquals(OutboxOperation.UPDATE, outboxRow.operation)
    }

    // saveTask: clientRef stability + the "edited twice before first push" case --------------------

    @Test
    fun `clientRef stays stable across two saves of the same still-pending task`() = runTest {
        val id = repository.saveTask(Task(id = 0, title = "v1"))
        val firstClientRef = database.outboxDao().getForTask(id)!!.clientRef

        repository.saveTask(database.taskDao().getByIdIncludingDeleted(id)!!.copy(title = "v2"))
        val secondClientRef = database.outboxDao().getForTask(id)!!.clientRef

        assertEquals(firstClientRef, secondClientRef)
    }

    @Test
    fun `a task edited a second time before its first push still queues as CREATE, since the server never got it`() = runTest {
        // Save it once (never yet pushed to the server -> serverId stays null).
        val id = repository.saveTask(Task(id = 0, title = "v1"))
        assertEquals(OutboxOperation.CREATE, database.outboxDao().getForTask(id)!!.operation)

        // Edit it again, exactly the "edited twice before the first push ever completes" scenario
        // from the feature's own architecture notes. The row still has no serverId — the server
        // has never heard of this task — so this must still queue as a CREATE. If it silently
        // became an UPDATE instead, SyncEngine.pushOne's UPDATE branch would find `serverId == null`
        // and *drop the outbox row entirely* (see its "should not happen" comment), permanently
        // losing this task: it would never reach the server as a create, ever.
        val reloaded = database.taskDao().getByIdIncludingDeleted(id)!!
        repository.saveTask(reloaded.copy(title = "v2"))

        val outboxRow = database.outboxDao().getForTask(id)!!
        assertEquals(OutboxOperation.CREATE, outboxRow.operation)
        assertEquals(SyncState.PENDING_CREATE, database.taskDao().getByIdIncludingDeleted(id)!!.syncState)
    }

    // deleteTask: already-synced task -> soft delete + DELETE outbox row -----------------------------

    @Test
    fun `deleteTask on an already-synced task soft-deletes locally and enqueues a DELETE row`() = runTest {
        val id = database.taskDao().insert(Task(title = "A borrar", serverId = 5L, updatedAt = 1L, syncState = SyncState.SYNCED))

        repository.deleteTask(id)

        val row = database.taskDao().getByIdIncludingDeleted(id)!!
        assertTrue(row.deleted)
        assertEquals(SyncState.PENDING_DELETE, row.syncState)
        assertEquals(fixedNow, row.updatedAt)

        val outboxRow = database.outboxDao().getForTask(id)!!
        assertEquals(OutboxOperation.DELETE, outboxRow.operation)
    }

    // deleteTask: never-synced task short-circuits (no server round-trip needed) --------------------

    @Test
    fun `deleteTask on a task that never reached the server hard-deletes immediately with no outbox row`() = runTest {
        val id = repository.saveTask(Task(id = 0, title = "Nunca sincronizada")) // serverId stays null

        repository.deleteTask(id)

        assertNull(database.taskDao().getByIdIncludingDeleted(id))
        assertNull(database.outboxDao().getForTask(id))
    }

    @Test
    fun `deleteTask on a never-synced task also drops any CREATE row still queued for it`() = runTest {
        val id = repository.saveTask(Task(id = 0, title = "Crear y borrar"))
        assertEquals(1, database.outboxDao().getAll().size)

        repository.deleteTask(id)

        assertTrue(database.outboxDao().getAll().isEmpty())
    }

    // Pass-throughs (delegate) ------------------------------------------------------------------

    @Test
    fun `observeTasks and observeTask forward to the delegate`() = runTest {
        val tasks = listOf(Task(id = 1, title = "A"), Task(id = 2, title = "B"))
        val delegateWithTasks = FakeTaskRepository(tasks)
        val repo = OutboxTaskRepository(database, delegateWithTasks, syncEngine, now = { fixedNow })

        assertEquals(tasks, repo.observeTasks().first())
        assertEquals(tasks[1], repo.observeTask(2).first())
    }

    @Test
    fun `startTimer and pauseTimer forward to the delegate`() = runTest {
        val delegateWithTask = FakeTaskRepository(listOf(Task(id = 1, title = "T")))
        val repo = OutboxTaskRepository(database, delegateWithTask, syncEngine, now = { fixedNow })

        repo.startTimer(1)
        repo.pauseTimer(1)

        assertEquals(listOf(1L), delegateWithTask.startedIds)
        assertEquals(listOf(1L), delegateWithTask.pausedIds)
    }
}
