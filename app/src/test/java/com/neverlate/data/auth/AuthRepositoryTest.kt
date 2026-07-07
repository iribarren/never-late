package com.neverlate.data.auth

import com.neverlate.data.sync.FakeTokenStorage
import com.neverlate.data.sync.FakeUserPreferencesRepository
import com.neverlate.data.sync.OutboxEntity
import com.neverlate.data.sync.OutboxOperation
import com.neverlate.data.tasks.NeverLateDatabase
import com.neverlate.data.sync.buildInMemoryTestDatabase
import com.neverlate.data.tasks.SyncState
import com.neverlate.data.tasks.Task
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
 * Tests [AuthRepositoryImpl] against a real [AuthApi] talking to a local [MockWebServer] (same
 * pattern as [com.neverlate.data.sync.SyncEngineTest]), covering the feature spec's *Acceptance
 * Criteria* items 1 (auth round-trip + readable errors), 2 (session persists, logout clears
 * everything) and the 401 -> logout path (item 10) at the repository level, one layer below
 * [com.neverlate.data.sync.SyncEngineTest]'s interceptor-triggered version.
 *
 * Needs the real, Robolectric-backed in-memory [NeverLateDatabase] (see
 * [buildInMemoryTestDatabase]) because [AuthRepositoryImpl.logout] uses `database.withTransaction`
 * to wipe tasks + outbox together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthRepositoryTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var database: NeverLateDatabase
    private lateinit var tokenStorage: FakeTokenStorage
    private lateinit var preferences: FakeUserPreferencesRepository
    private lateinit var repository: AuthRepositoryImpl

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        database = buildInMemoryTestDatabase()
        tokenStorage = FakeTokenStorage()
        preferences = FakeUserPreferencesRepository()
        val api = AuthNetwork.create(baseUrl = mockWebServer.url("/").toString())
        repository = AuthRepositoryImpl(api, tokenStorage, database, preferences)
    }

    @After
    fun tearDown() {
        database.close()
        try {
            mockWebServer.shutdown()
        } catch (error: Exception) {
            // The "server unreachable" test already shut it down; nothing left to clean up.
        }
    }

    // register / login success ------------------------------------------------------------------

    @Test
    fun `register with valid new credentials stores the session and flips authState to LoggedIn`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(201).setBody("""{"accessToken":"jwt-1","refreshToken":"refresh-1","user":{"id":1,"email":"new@example.com"}}"""))

        val result = repository.register("new@example.com", "supersecret1")

        assertEquals(AuthResult.Success, result)
        assertEquals("jwt-1", tokenStorage.getAccessToken())
        assertEquals(1L, tokenStorage.getUserId())
        assertEquals("new@example.com", tokenStorage.getUserEmail())
        assertEquals(AuthState.LoggedIn(1L, "new@example.com"), repository.authState.value)
    }

    @Test
    fun `login with valid existing credentials stores the session and flips authState to LoggedIn`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt-2","refreshToken":"refresh-2","user":{"id":7,"email":"existing@example.com"}}"""))

        val result = repository.login("existing@example.com", "supersecret1")

        assertEquals(AuthResult.Success, result)
        assertEquals("jwt-2", tokenStorage.getAccessToken())
        assertEquals(AuthState.LoggedIn(7L, "existing@example.com"), repository.authState.value)
    }

    // register / login errors --------------------------------------------------------------------

    @Test
    fun `register with an email already taken returns EMAIL_TAKEN and saves no token`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"email_taken","message":"Email already registered"}}"""))

        val result = repository.register("taken@example.com", "supersecret1")

        assertEquals(AuthResult.Error(AuthErrorType.EMAIL_TAKEN), result)
        assertNull(tokenStorage.getAccessToken())
        assertEquals(AuthState.LoggedOut, repository.authState.value)
    }

    @Test
    fun `login with wrong credentials returns INVALID_CREDENTIALS and saves no token`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"invalid_credentials","message":"Wrong email or password"}}"""))

        val result = repository.login("someone@example.com", "wrongpassword")

        assertEquals(AuthResult.Error(AuthErrorType.INVALID_CREDENTIALS), result)
        assertNull(tokenStorage.getAccessToken())
    }

    @Test
    fun `register with a malformed body returns VALIDATION and saves no token`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"validation_error","message":"Password too short"}}"""))

        val result = repository.register("bad", "short")

        assertEquals(AuthResult.Error(AuthErrorType.VALIDATION), result)
        assertNull(tokenStorage.getAccessToken())
    }

    @Test
    fun `login when the server is unreachable returns NETWORK`() = runTest {
        mockWebServer.shutdown()

        val result = repository.login("someone@example.com", "supersecret1")

        assertEquals(AuthResult.Error(AuthErrorType.NETWORK), result)
    }

    // Session persistence (US-2) ----------------------------------------------------------------

    @Test
    fun `a session already present in TokenStorage at construction time is reflected immediately as LoggedIn`() {
        val prefilledTokenStorage = FakeTokenStorage(token = "existing-jwt", userId = 3L, email = "restored@example.com")

        val restored = AuthRepositoryImpl(
            AuthNetwork.create(baseUrl = mockWebServer.url("/").toString()),
            prefilledTokenStorage,
            database,
            preferences,
        )

        assertEquals(AuthState.LoggedIn(3L, "restored@example.com"), restored.authState.value)
    }

    // logout --------------------------------------------------------------------------------------

    @Test
    fun `logout clears the token, wipes cached tasks and outbox, resets the sync cursor, and flips authState to LoggedOut`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt-3","refreshToken":"refresh-3","user":{"id":9,"email":"leaving@example.com"}}"""))
        repository.login("leaving@example.com", "supersecret1")
        assertTrue(repository.authState.value is AuthState.LoggedIn)
        mockWebServer.takeRequest() // drain the /auth/login request so the next takeRequest() below is /auth/logout

        val taskId = database.taskDao().insert(Task(title = "Cached task", serverId = 1L, updatedAt = 1L, syncState = SyncState.SYNCED))
        database.outboxDao().enqueue(OutboxEntity(taskLocalId = taskId, operation = OutboxOperation.UPDATE, clientRef = "ref", enqueuedAt = 1L))
        preferences.saveSyncCursor(555L)

        // logout() best-effort revokes the refresh token server-side (contract §2.3) before
        // clearing local state — see the "revoke fails" test below for the offline case.
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        repository.logout()

        assertNull(tokenStorage.getAccessToken())
        assertNull(tokenStorage.getRefreshToken())
        assertNull(database.taskDao().getByIdIncludingDeleted(taskId))
        assertTrue(database.outboxDao().getAll().isEmpty())
        assertEquals(0L, preferences.userPreferences.value.syncCursor)
        assertEquals(AuthState.LoggedOut, repository.authState.value)

        val logoutRequest = mockWebServer.takeRequest()
        assertEquals("POST", logoutRequest.method)
        assertTrue(logoutRequest.path!!.endsWith("/auth/logout"))
        assertTrue(logoutRequest.body.readUtf8().contains("\"refresh-3\""))
    }

    @Test
    fun `logout clears local state even when the revoke call fails`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"jwt-4","refreshToken":"refresh-4","user":{"id":11,"email":"offline@example.com"}}"""))
        repository.login("offline@example.com", "supersecret1")

        // The revoke call itself fails (server unreachable) — logout must still fully clear the
        // local session (US-4: best-effort revoke, unconditional local logout).
        mockWebServer.shutdown()

        repository.logout()

        assertNull(tokenStorage.getAccessToken())
        assertNull(tokenStorage.getRefreshToken())
        assertEquals(AuthState.LoggedOut, repository.authState.value)
    }

    // Note on the 401 -> logout path (US-2, acceptance criterion 10): `notifyUnauthorized()` (called
    // by com.neverlate.data.sync.AuthInterceptor on any 401) is intentionally not exercised here —
    // it launches `logout()` on AuthRepositoryImpl's own fire-and-forget CoroutineScope(Dispatchers.IO)
    // (it has no coroutine of its own to suspend from, being invoked from a synchronous OkHttp
    // interceptor callback), with no way to await its completion from a test. Polling for it proved
    // flaky under Robolectric (observed real delays past 2s, then the deferred logout() crashing a
    // *later* test against its by-then-closed in-memory database) — a brittle, nondeterministic test
    // this project's conventions rule out. `logout()`'s own behaviour is fully covered above; the
    // interceptor side (a 401 response invokes `onUnauthorized`) is covered by
    // `com.neverlate.data.sync.SyncEngineTest`'s "a 401 on pull notifies onUnauthorized" test.
}
