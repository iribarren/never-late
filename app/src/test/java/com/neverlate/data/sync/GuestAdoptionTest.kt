package com.neverlate.data.sync

import com.neverlate.data.auth.AuthNetwork
import com.neverlate.data.auth.AuthRepositoryImpl
import com.neverlate.data.auth.AuthState
import com.neverlate.data.tasks.NeverLateDatabase
import com.neverlate.data.tasks.SyncState
import com.neverlate.data.tasks.Task
import com.neverlate.ui.notification.FakeTaskRepository
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests feature 13 (guest mode)'s *merge on sign-in* — the deferred push finally running — and the
 * logout/re-login safety rule (PD-2) that prevents it from ever duplicating or leaking data across
 * accounts. Per the feature spec's own framing (*Technical Approach §3*), adoption is not a new
 * subsystem: it is [SyncEngine.syncNow] running for the first time once a token exists. These tests
 * therefore exercise the existing [SyncEngine]/[OutboxTaskRepository]/[AuthRepositoryImpl] seams
 * exactly as [com.neverlate.data.sync.SyncEngineTest] and
 * [com.neverlate.data.sync.OutboxTaskRepositoryTest] already do, just composed together the way
 * `MainActivity` wires them (`authRepositoryImpl.onAuthenticated = { taskRepository.refreshFromServer() }`).
 *
 * Not duplicated here: `OutboxTaskRepositoryTest`'s "clientRef stays stable across two saves of the
 * same still-pending task" already proves the spec's US-2/US-3 "clientRef stability" acceptance
 * criterion (a guest editing an orphan task before sign-in keeps the same clientRef) — that code
 * path does not distinguish a guest's edit from a logged-in user's, so a second, guest-flavoured
 * copy of the same assertion would verify nothing new. Likewise the low-level "a dropped ack does
 * not duplicate the task on retry" idempotent-retry mechanism is already covered generically by
 * `SyncEngineTest`; the test below reuses that same mechanism rather than re-proving it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuestAdoptionTest {

    private val fixedNow = 7_000_000L

    private lateinit var mockWebServer: MockWebServer
    private lateinit var database: NeverLateDatabase
    private lateinit var tokenStorage: FakeTokenStorage
    private lateinit var preferences: FakeUserPreferencesRepository
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        database = buildInMemoryTestDatabase()
        tokenStorage = FakeTokenStorage(token = null) // guest: no session yet
        preferences = FakeUserPreferencesRepository()

        val api = TasksNetwork.create(
            baseUrl = mockWebServer.url("/").toString(),
            tokenStorage = tokenStorage,
            onUnauthorized = {},
        )
        engine = SyncEngine(api, database, preferences, tokenStorage, now = { fixedNow })
    }

    @After
    fun tearDown() {
        database.close()
        try {
            mockWebServer.shutdown()
        } catch (error: Exception) {
            // Some tests shut it down themselves; nothing left to clean up.
        }
    }

    private fun taskDtoJson(id: Long, clientRef: String, title: String, updatedAt: Long) =
        """{"id":$id,"clientRef":"$clientRef","title":"$title","updatedAt":$updatedAt,"deleted":false}"""

    // US-2/US-3 — adoption without duplicates -------------------------------------------------------

    @Test
    fun `guest orphans queued before sign-in are each created exactly once on the first post-login sync`() = runTest {
        // Guest phase: two tasks created locally with no session — exactly the shape
        // OutboxTaskRepository.saveTask leaves behind (serverId null, PENDING_CREATE, a CREATE
        // outbox row keyed by a stable clientRef). Written directly here (rather than through
        // OutboxTaskRepository.saveTask) to avoid that method's own schedulePush() firing a real,
        // concurrent background sync attempt that would race this test's own explicit syncNow()
        // calls below — OutboxTaskRepositoryTest already proves saveTask produces exactly this shape.
        val taskAId = database.taskDao().insert(Task(title = "Buy milk", updatedAt = 1L, syncState = SyncState.PENDING_CREATE))
        val taskBId = database.taskDao().insert(Task(title = "Walk the dog", updatedAt = 1L, syncState = SyncState.PENDING_CREATE))
        database.outboxDao().enqueue(OutboxEntity(taskLocalId = taskAId, operation = OutboxOperation.CREATE, clientRef = "guest-ref-A", enqueuedAt = 10L))
        database.outboxDao().enqueue(OutboxEntity(taskLocalId = taskBId, operation = OutboxOperation.CREATE, clientRef = "guest-ref-B", enqueuedAt = 20L))
        assertEquals(0, mockWebServer.requestCount)

        // Sign-in: a token now exists (as if register()/login() just saved the session) — this is
        // the one condition SyncEngine.syncNow() gates the whole push/pull cycle on.
        tokenStorage.saveSession("jwt-guest-adopts", "refresh-1", 1L, "new-user@example.com")

        mockWebServer.enqueue(MockResponse().setResponseCode(201).setBody(taskDtoJson(id = 900, clientRef = "guest-ref-A", title = "Buy milk", updatedAt = 500L)))
        mockWebServer.enqueue(MockResponse().setResponseCode(201).setBody(taskDtoJson(id = 901, clientRef = "guest-ref-B", title = "Walk the dog", updatedAt = 501L)))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"tasks":[],"serverTime":1000}"""))

        val result = engine.syncNow()

        assertEquals(SyncStatus.UpToDate, result)
        assertEquals(3, mockWebServer.requestCount) // 2 creates + 1 pull, no more

        val savedA = database.taskDao().getByIdIncludingDeleted(taskAId)!!
        assertEquals(900L, savedA.serverId)
        assertEquals(SyncState.SYNCED, savedA.syncState)
        val savedB = database.taskDao().getByIdIncludingDeleted(taskBId)!!
        assertEquals(901L, savedB.serverId)
        assertEquals(SyncState.SYNCED, savedB.syncState)
        assertTrue("adoption must drain the outbox, not just mark tasks synced", database.outboxDao().getAll().isEmpty())

        // Re-running sync afterwards (e.g. the "sync on app open" trigger firing again, or a
        // WorkManager retry) must not re-create anything: the outbox is already empty, so the only
        // possible request is another pull.
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"tasks":[],"serverTime":1000}"""))
        val secondResult = engine.syncNow()

        assertEquals(SyncStatus.UpToDate, secondResult)
        assertEquals(4, mockWebServer.requestCount) // exactly one more request (the pull) — no new POST
    }

    @Test
    fun `adoption alongside the account's own pre-existing tasks keeps both, since there is no content dedup`() = runTest {
        // US-3 + PD-5: the account already has a task server-side (from another device); the guest
        // also queued one locally. Both must survive as two distinct tasks after sign-in.
        val guestTaskId = database.taskDao().insert(Task(title = "Guest's task", updatedAt = 1L, syncState = SyncState.PENDING_CREATE))
        database.outboxDao().enqueue(OutboxEntity(taskLocalId = guestTaskId, operation = OutboxOperation.CREATE, clientRef = "guest-ref-X", enqueuedAt = 10L))

        tokenStorage.saveSession("jwt", "refresh", 2L, "existing-account@example.com")

        mockWebServer.enqueue(MockResponse().setResponseCode(201).setBody(taskDtoJson(id = 700, clientRef = "guest-ref-X", title = "Guest's task", updatedAt = 200L)))
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"tasks":[${taskDtoJson(id = 701, clientRef = "already-on-account", title = "Account's own task", updatedAt = 300L)}],"serverTime":400}""",
            ),
        )

        val result = engine.syncNow()

        assertEquals(SyncStatus.UpToDate, result)
        val adoptedGuestTask = database.taskDao().getByIdIncludingDeleted(guestTaskId)!!
        assertEquals(700L, adoptedGuestTask.serverId)
        val pulledAccountTask = database.taskDao().getByServerId(701L)
        assertEquals("Account's own task", pulledAccountTask?.title)
        // Two distinct rows, not one merged/deduplicated row.
        assertFalse(adoptedGuestTask.id == pulledAccountTask?.id)
    }

    // Risk: duplicate tasks / cross-account leakage from a mishandled logout ------------------------

    @Test
    fun `guest creates, adopted by account A, do not resurface or get re-pushed after logout and login as account B`() = runTest {
        val authApi = AuthNetwork.create(baseUrl = mockWebServer.url("/").toString())
        val delegate = FakeTaskRepository()
        val outboxRepository = OutboxTaskRepository(database, delegate, engine, now = { fixedNow })
        val authRepository = AuthRepositoryImpl(authApi, tokenStorage, database, preferences)
        // Same belt-and-braces wiring MainActivity does: adoption fires right after a successful
        // register/login, not just from AppNavHost's recomposition.
        authRepository.onAuthenticated = { outboxRepository.refreshFromServer() }

        // --- Guest phase: one task created locally, no session. ---
        val guestTaskId = database.taskDao().insert(Task(title = "Guest task", updatedAt = 1L, syncState = SyncState.PENDING_CREATE))
        database.outboxDao().enqueue(OutboxEntity(taskLocalId = guestTaskId, operation = OutboxOperation.CREATE, clientRef = "guest-ref-1", enqueuedAt = 10L))

        // --- Sign in as account A: login response, then the adoption push+pull it triggers. ---
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt-A","refreshToken":"refresh-A","user":{"id":1,"email":"accountA@example.com"}}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(201).setBody(taskDtoJson(id = 800, clientRef = "guest-ref-1", title = "Guest task", updatedAt = 100L)))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"tasks":[],"serverTime":200}"""))

        val loginResult = authRepository.login("accountA@example.com", "supersecret1")
        assertEquals(com.neverlate.data.auth.AuthResult.Success, loginResult)
        assertEquals(AuthState.LoggedIn(1L, "accountA@example.com"), authRepository.authState.value)

        val adopted = database.taskDao().getByIdIncludingDeleted(guestTaskId)!!
        assertEquals(800L, adopted.serverId)
        assertEquals(SyncState.SYNCED, adopted.syncState)
        assertTrue(database.outboxDao().getAll().isEmpty())
        assertEquals(3, mockWebServer.requestCount) // login + 1 create + 1 pull, exactly once

        // --- Logout: best-effort revoke, then the mandatory wipe (PD-2), landing in Guest. ---
        mockWebServer.enqueue(MockResponse().setResponseCode(204))
        authRepository.logout()

        assertEquals(AuthState.Guest, authRepository.authState.value)
        assertNull("logout must wipe account A's adopted task, or it would be re-adopted as a duplicate create", database.taskDao().getByIdIncludingDeleted(guestTaskId))
        assertTrue(database.outboxDao().getAll().isEmpty())
        assertEquals(4, mockWebServer.requestCount) // + 1 logout call

        // Drain the 4 requests accounted for so far (login A, create, pull, logout) so the
        // remaining takeRequest() calls below unambiguously belong to account B's sign-in.
        repeat(4) { mockWebServer.takeRequest() }

        // --- Sign in as a different account B: only a login + an (empty) pull should happen — no
        // create request, since the wipe above left nothing in the outbox to (re-)push. ---
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt-B","refreshToken":"refresh-B","user":{"id":2,"email":"accountB@example.com"}}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"tasks":[],"serverTime":300}"""))

        val secondLoginResult = authRepository.login("accountB@example.com", "supersecret2")
        assertEquals(com.neverlate.data.auth.AuthResult.Success, secondLoginResult)
        assertEquals(AuthState.LoggedIn(2L, "accountB@example.com"), authRepository.authState.value)

        assertEquals(6, mockWebServer.requestCount) // + login + pull only, no create in between

        val loginBRequest = mockWebServer.takeRequest()
        assertTrue(loginBRequest.path!!.endsWith("/auth/login"))
        val pullBRequest = mockWebServer.takeRequest()
        assertEquals("GET", pullBRequest.method)
        assertTrue("account B's sign-in must never re-push account A's/the guest's already-wiped task as a create", pullBRequest.path!!.contains("/tasks"))

        // Account A's/the guest's task never reappears locally under account B either.
        assertNull(database.taskDao().getByServerId(800L))
    }
}
