package com.neverlate.data.sync

import com.neverlate.data.tasks.NeverLateDatabase
import com.neverlate.data.tasks.SyncState
import com.neverlate.data.tasks.Task
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration-style tests for [SyncEngine]'s push/pull cycle: a real [TasksApi] (via
 * [TasksNetwork.create], same as [com.neverlate.MainActivity] builds it) talks to a local
 * [MockWebServer] instead of the real backend, and a real **in-memory** [NeverLateDatabase]
 * (Robolectric-backed, see [buildInMemoryTestDatabase]) stands in for the device's Room database —
 * needed here (unlike feature 10's [com.neverlate.data.articles.CachingArticleRepositoryTest])
 * because [SyncEngine] relies on `database.withTransaction`'s real transactional guarantees, which
 * a hand-written DAO fake cannot provide.
 *
 * Covers the feature spec's *Acceptance Criteria* items 4-6, 8 and 10: push replay order + server
 * id assignment, idempotent retry, tombstone propagation both directions, pull upsert + cursor
 * advance, and the 401 -> logout path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineTest {

    private val fixedNow = 5_000_000L

    private lateinit var mockWebServer: MockWebServer
    private lateinit var database: NeverLateDatabase
    private lateinit var tokenStorage: FakeTokenStorage
    private lateinit var preferences: FakeUserPreferencesRepository
    private var unauthorizedCount = 0
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        database = buildInMemoryTestDatabase()
        tokenStorage = FakeTokenStorage(token = "session-token", userId = 1L, email = "user@example.com")
        preferences = FakeUserPreferencesRepository()
        unauthorizedCount = 0

        val api = TasksNetwork.create(
            baseUrl = mockWebServer.url("/").toString(),
            tokenStorage = tokenStorage,
            onUnauthorized = { unauthorizedCount++ },
        )
        engine = SyncEngine(api, database, preferences, tokenStorage, now = { fixedNow })
    }

    @After
    fun tearDown() {
        database.close()
        try {
            mockWebServer.shutdown()
        } catch (error: Exception) {
            // Some tests kill the server themselves; nothing left to clean up.
        }
    }

    private fun taskDtoJson(
        id: Long,
        clientRef: String,
        title: String = "Task",
        updatedAt: Long,
        deleted: Boolean = false,
    ) = """{"id":$id,"clientRef":"$clientRef","title":"$title","updatedAt":$updatedAt,"deleted":$deleted}"""

    // Idle / no session -----------------------------------------------------------------------------

    @Test
    fun `syncNow with no session does nothing and stays idle`() = runTest {
        tokenStorage.clearSession()

        val result = engine.syncNow()

        assertEquals(SyncStatus.Idle, result)
        assertEquals(0, mockWebServer.requestCount)
    }

    // Push --------------------------------------------------------------------------------------

    @Test
    fun `push replays outbox oldest-first, assigns each task its serverId, and clears the outbox`() = runTest {
        val taskAId = database.taskDao().insert(Task(title = "A", updatedAt = 1L, syncState = SyncState.PENDING_CREATE))
        val taskBId = database.taskDao().insert(Task(title = "B", updatedAt = 1L, syncState = SyncState.PENDING_CREATE))
        // B is queued *before* A (smaller enqueuedAt) despite being inserted second locally — the
        // outbox replay order must follow enqueuedAt, not insertion/local-id order.
        database.outboxDao().enqueue(OutboxEntity(taskLocalId = taskBId, operation = OutboxOperation.CREATE, clientRef = "ref-B", enqueuedAt = 100L))
        database.outboxDao().enqueue(OutboxEntity(taskLocalId = taskAId, operation = OutboxOperation.CREATE, clientRef = "ref-A", enqueuedAt = 200L))

        mockWebServer.enqueue(MockResponse().setResponseCode(201).setBody(taskDtoJson(id = 501, clientRef = "ref-B", title = "B", updatedAt = 999L)))
        mockWebServer.enqueue(MockResponse().setResponseCode(201).setBody(taskDtoJson(id = 502, clientRef = "ref-A", title = "A", updatedAt = 998L)))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"tasks":[],"serverTime":12345}"""))

        val result = engine.syncNow()

        assertEquals(SyncStatus.UpToDate, result)

        val firstRequest = mockWebServer.takeRequest()
        assertEquals("POST", firstRequest.method)
        assertTrue("expected B's clientRef to be pushed first", firstRequest.body.readUtf8().contains("\"ref-B\""))

        val secondRequest = mockWebServer.takeRequest()
        assertEquals("POST", secondRequest.method)
        assertTrue("expected A's clientRef to be pushed second", secondRequest.body.readUtf8().contains("\"ref-A\""))

        val thirdRequest = mockWebServer.takeRequest()
        assertEquals("GET", thirdRequest.method)

        val savedB = database.taskDao().getByIdIncludingDeleted(taskBId)!!
        assertEquals(501L, savedB.serverId)
        assertEquals(SyncState.SYNCED, savedB.syncState)

        val savedA = database.taskDao().getByIdIncludingDeleted(taskAId)!!
        assertEquals(502L, savedA.serverId)
        assertEquals(SyncState.SYNCED, savedA.syncState)

        assertTrue(database.outboxDao().getAll().isEmpty())
        assertEquals(12345L, preferences.userPreferences.value.syncCursor)
    }

    @Test
    fun `a dropped ack does not duplicate the task on retry - the same clientRef is replayed`() = runTest {
        val taskId = database.taskDao().insert(Task(title = "Flaky create", updatedAt = 1L, syncState = SyncState.PENDING_CREATE))
        database.outboxDao().enqueue(OutboxEntity(taskLocalId = taskId, operation = OutboxOperation.CREATE, clientRef = "stable-ref", enqueuedAt = 1L))

        // First attempt: the server receives the request but the connection drops before the
        // client ever sees the ack (the classic "did it actually create it?" scenario).
        mockWebServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        val firstResult = engine.syncNow()

        assertEquals(SyncStatus.Offline, firstResult)
        val droppedRequest = mockWebServer.takeRequest()
        assertTrue(droppedRequest.body.readUtf8().contains("\"stable-ref\""))
        // The row must still be queued — an IOException must never clear the outbox entry.
        assertEquals(1, database.outboxDao().getAll().size)
        assertEquals(SyncState.PENDING_CREATE, database.taskDao().getByIdIncludingDeleted(taskId)?.syncState)

        // Second attempt: connectivity is back. In real life the server dedupes by clientRef and
        // returns the task it already created; here we simulate that by answering normally.
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(taskDtoJson(id = 900, clientRef = "stable-ref", title = "Flaky create", updatedAt = 50L)))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"tasks":[],"serverTime":60}"""))

        val secondResult = engine.syncNow()

        assertEquals(SyncStatus.UpToDate, secondResult)
        val retriedRequest = mockWebServer.takeRequest()
        // The client resends the exact same clientRef — it never mints a fresh one for a retry.
        assertTrue(retriedRequest.body.readUtf8().contains("\"stable-ref\""))

        assertTrue(database.outboxDao().getAll().isEmpty())
        val allTasks = database.taskDao().getByIdIncludingDeleted(taskId)
        assertEquals(900L, allTasks?.serverId)
        assertEquals(SyncState.SYNCED, allTasks?.syncState)
        // No duplicate local row was created for the retried push.
        assertEquals(1, countAllTasks())
    }

    private suspend fun countAllTasks(): Int {
        // No "count all" query exists on TaskDao; observeTasks filters tombstones, so read via
        // the one-shot lookup instead for a small, known id range in these tests.
        var count = 0
        for (id in 1..20) if (database.taskDao().getByIdIncludingDeleted(id.toLong()) != null) count++
        return count
    }

    // Tombstones ----------------------------------------------------------------------------------

    @Test
    fun `a local pending delete pushes a DELETE and hard-deletes the row on ack`() = runTest {
        val taskId = database.taskDao().insert(
            Task(title = "To delete", serverId = 77L, updatedAt = 1L, syncState = SyncState.SYNCED),
        )
        database.taskDao().update(
            database.taskDao().getByIdIncludingDeleted(taskId)!!
                .copy(deleted = true, syncState = SyncState.PENDING_DELETE, updatedAt = 2L),
        )
        database.outboxDao().enqueue(OutboxEntity(taskLocalId = taskId, operation = OutboxOperation.DELETE, clientRef = "del-ref", enqueuedAt = 1L))

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(taskDtoJson(id = 77, clientRef = "del-ref", updatedAt = 3L, deleted = true)))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"tasks":[],"serverTime":100}"""))

        val result = engine.syncNow()

        assertEquals(SyncStatus.UpToDate, result)
        val deleteRequest = mockWebServer.takeRequest()
        assertEquals("DELETE", deleteRequest.method)
        assertTrue(deleteRequest.path!!.endsWith("/tasks/77"))

        assertNull(database.taskDao().getByIdIncludingDeleted(taskId))
        assertTrue(database.outboxDao().getAll().isEmpty())
    }

    @Test
    fun `a pulled tombstone removes the local row`() = runTest {
        val taskId = database.taskDao().insert(
            Task(title = "Deleted on another device", serverId = 55L, updatedAt = 1L, syncState = SyncState.SYNCED),
        )

        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"tasks":[${taskDtoJson(id = 55, clientRef = "whatever", updatedAt = 999L, deleted = true)}],"serverTime":999}""",
            ),
        )

        val result = engine.syncNow()

        assertEquals(SyncStatus.UpToDate, result)
        assertNull(database.taskDao().getByIdIncludingDeleted(taskId))
        assertEquals(999L, preferences.userPreferences.value.syncCursor)
    }

    // Pull ---------------------------------------------------------------------------------------

    @Test
    fun `pull upserts a brand-new remote task the device never had and advances the cursor`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"tasks":[${taskDtoJson(id = 10, clientRef = "c1", title = "From another device", updatedAt = 42L)}],"serverTime":4321}""",
            ),
        )

        val result = engine.syncNow()

        assertEquals(SyncStatus.UpToDate, result)
        val inserted = database.taskDao().getByServerId(10L)
        assertEquals("From another device", inserted?.title)
        assertEquals(SyncState.SYNCED, inserted?.syncState)
        assertEquals(4321L, preferences.userPreferences.value.syncCursor)
    }

    // 401 -----------------------------------------------------------------------------------------

    @Test
    fun `a 401 on pull notifies onUnauthorized and reports Error`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"expired"}}"""))

        val result = engine.syncNow()

        assertEquals(SyncStatus.Error, result)
        assertEquals(1, unauthorizedCount)
    }
}
