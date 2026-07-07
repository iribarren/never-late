package com.neverlate.data.sync

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

/**
 * Tests for [TokenAuthenticator] — the OkHttp `Authenticator` wired into [TasksNetwork] (feature
 * 12) that renews an expired access token transparently on a `401` and retries the original
 * request (US-1), falls back to logout only once renewal itself is impossible (US-2), shares a
 * single refresh across a burst of concurrent `401`s (US-3), and atomically rotates both stored
 * tokens (US-4). Uses a real [TasksApi] built by [TasksNetwork.create] (same wiring
 * [com.neverlate.MainActivity] uses), talking to a local [MockWebServer] — no Robolectric needed
 * here, unlike [SyncEngineTest]/[com.neverlate.data.auth.AuthRepositoryTest], since nothing on
 * this path touches Room.
 */
class TokenAuthenticatorTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var tokenStorage: FakeTokenStorage
    private var unauthorizedCount = 0

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        tokenStorage = FakeTokenStorage(token = "old-access", refreshToken = "old-refresh", userId = 1L, email = "user@example.com")
        unauthorizedCount = 0
    }

    @After
    fun tearDown() {
        try {
            mockWebServer.shutdown()
        } catch (error: Exception) {
            // The single-flight test replaces the dispatcher and never shuts the server down twice;
            // other tests may already have (or the server may already be down) — nothing left to do.
        }
    }

    private fun buildApi(): TasksApi = TasksNetwork.create(
        baseUrl = mockWebServer.url("/").toString(),
        tokenStorage = tokenStorage,
        onUnauthorized = { unauthorizedCount++ },
    )

    // US-1: silent renewal + retry -------------------------------------------------------------

    @Test
    fun `a 401 triggers a silent refresh and retries the original request unchanged with the new token`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"expired"}}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"new-access","refreshToken":"new-refresh","user":{"id":1,"email":"user@example.com"}}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":9,"clientRef":"c-1","title":"Buy milk","updatedAt":5}"""))

        val api = buildApi()
        val result = api.createTask(CreateTaskRequest(clientRef = "c-1", title = "Buy milk", updatedAt = 5L))

        assertEquals(9L, result.id)
        assertEquals(0, unauthorizedCount)

        val failedRequest = mockWebServer.takeRequest()
        assertTrue(failedRequest.path!!.endsWith("/tasks"))
        assertEquals("Bearer old-access", failedRequest.getHeader("Authorization"))
        val originalBody = failedRequest.body.readUtf8()
        assertTrue(originalBody.contains("\"c-1\""))

        val refreshRequest = mockWebServer.takeRequest()
        assertTrue(refreshRequest.path!!.endsWith("/auth/refresh"))
        assertTrue(refreshRequest.body.readUtf8().contains("\"old-refresh\""))

        val retriedRequest = mockWebServer.takeRequest()
        assertTrue(retriedRequest.path!!.endsWith("/tasks"))
        assertEquals("POST", retriedRequest.method)
        assertEquals("Bearer new-access", retriedRequest.getHeader("Authorization"))
        // The exact same request is replayed - not a fresh/different one, and not lost.
        assertEquals(originalBody, retriedRequest.body.readUtf8())

        // US-4: both tokens landed together, in one atomic write.
        assertEquals("new-access", tokenStorage.getAccessToken())
        assertEquals("new-refresh", tokenStorage.getRefreshToken())
        assertEquals(listOf("new-access" to "new-refresh"), tokenStorage.savedTokenPairs)
    }

    // US-2: renewal itself fails -> fall back to logout, no retry loop --------------------------

    @Test
    fun `when refresh itself returns 401, the authenticator gives up without retrying and notifies exactly once`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"expired"}}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"invalid_refresh_token","message":"expired"}}"""))

        val api = buildApi()

        val error = try {
            api.getTasks(since = 0L)
            null
        } catch (httpException: HttpException) {
            httpException
        }

        assertNotNull("expected the original 401 to surface once renewal fails", error)
        assertEquals(401, error!!.code())
        assertEquals(1, unauthorizedCount)
        // Exactly two requests total (the original call + one refresh attempt) - no retry loop.
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `when there is no refresh token to renew with, the authenticator gives up immediately without calling refresh`() = runTest {
        tokenStorage.clearSession()
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"expired"}}"""))

        val api = buildApi()

        val error = try {
            api.getTasks(since = 0L)
            null
        } catch (httpException: HttpException) {
            httpException
        }

        assertNotNull(error)
        assertEquals(1, unauthorizedCount)
        // No /auth/refresh call was even attempted.
        assertEquals(1, mockWebServer.requestCount)
    }

    // US-3: a burst of concurrent 401s shares exactly one refresh ------------------------------

    @Test
    fun `several concurrent 401s trigger exactly one refresh call, and every request retries with the new token`() = runTest {
        val refreshCallCount = AtomicInteger(0)
        val newAccessToken = "new-access-token"
        val newRefreshToken = "new-refresh-token"

        // A path/header-driven fake instead of a fixed enqueue() order: with several requests
        // racing for real, the arrival order at the server is not deterministic, so correctness
        // must not depend on it - only "exactly one /auth/refresh call happens" does.
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.contains("/auth/refresh") == true -> {
                    refreshCallCount.incrementAndGet()
                    MockResponse().setResponseCode(200).setBody(
                        """{"accessToken":"$newAccessToken","refreshToken":"$newRefreshToken","user":{"id":1,"email":"user@example.com"}}""",
                    )
                }
                request.getHeader("Authorization") == "Bearer $newAccessToken" ->
                    MockResponse().setResponseCode(200).setBody("""{"tasks":[],"serverTime":42}""")
                else ->
                    MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"expired"}}""")
            }
        }

        val api = buildApi()
        val concurrentCallCount = 6
        val results = (1..concurrentCallCount).map {
            async(Dispatchers.IO) { api.getTasks(since = 0L) }
        }.awaitAll()

        assertEquals(concurrentCallCount, results.size)
        assertEquals("exactly one refresh should have been issued for the whole burst", 1, refreshCallCount.get())
        assertEquals(0, unauthorizedCount)
        assertEquals(newAccessToken, tokenStorage.getAccessToken())
        assertEquals(newRefreshToken, tokenStorage.getRefreshToken())
    }
}
