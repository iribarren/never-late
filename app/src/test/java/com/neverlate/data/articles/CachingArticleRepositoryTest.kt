package com.neverlate.data.articles

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hand-written in-memory fake for [ArticleDao]. Room's generated DAO implementation needs an
 * Android runtime (or Robolectric) to actually run SQL, neither of which this plain-JVM test uses
 * — a `MutableMap` keyed by [ArticleEntity.id] gives [CachingArticleRepository] the exact same
 * upsert/clear/read semantics the real, Room-generated `ArticleDao` provides.
 */
private class FakeArticleDao : ArticleDao {
    private val rows = mutableMapOf<String, ArticleEntity>()

    override suspend fun getAll(): List<ArticleEntity> = rows.values.toList()

    override suspend fun getById(id: String): ArticleEntity? = rows[id]

    override suspend fun upsertAll(items: List<ArticleEntity>) {
        items.forEach { rows[it.id] = it }
    }

    override suspend fun clear() {
        rows.clear()
    }
}

/** The actual wire shape the remote API sends (see docs/articles-api/articles.json): snake_case
 * `article_id`, `content` instead of `body`, and no `summary` field at all. */
private const val WIRE_JSON = """
[
  {"article_id": "pomodoro", "title": "La técnica Pomodoro", "content": "Contenido completo sobre Pomodoro."},
  {"article_id": "time-blocking", "title": "Time blocking", "content": "Contenido completo sobre time blocking."}
]
"""

/**
 * Integration-style test for [CachingArticleRepository]: a real [ArticlesApi] — built exactly the
 * way [com.neverlate.MainActivity] builds it, via [ArticlesNetwork.create] — talks HTTP to a local
 * [MockWebServer] instead of the real network, paired with [FakeArticleDao] instead of a real Room
 * database. This is the "repositorio con servidor mock" the feature 10 prompt calls for, kept on
 * the plain JVM (no emulator/Robolectric needed) by faking only the one piece (the DAO) that would
 * otherwise require Room/Android.
 */
class CachingArticleRepositoryTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var dao: FakeArticleDao
    private lateinit var repository: CachingArticleRepository

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        dao = FakeArticleDao()
        val api = ArticlesNetwork.create(baseUrl = mockWebServer.url("/").toString())
        repository = CachingArticleRepository(api = api, dao = dao)
    }

    @After
    fun tearDown() {
        try {
            mockWebServer.shutdown()
        } catch (error: Exception) {
            // A test that simulates a dead server already shut it down; nothing left to clean up.
        }
    }

    @Test
    fun `refresh on 200 maps the wire JSON to domain articles and stores them in the cache`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(WIRE_JSON))

        val result = repository.refresh()

        assertTrue(result is RefreshResult.Success)
        assertEquals(2, dao.getAll().size)

        val cached = repository.getArticles()
        val pomodoro = cached.first { it.id == "pomodoro" }
        assertEquals("La técnica Pomodoro", pomodoro.title)
        assertEquals("Contenido completo sobre Pomodoro.", pomodoro.body)
        assertEquals(summarize("Contenido completo sobre Pomodoro."), pomodoro.summary)

        val timeBlocking = cached.first { it.id == "time-blocking" }
        assertEquals("Time blocking", timeBlocking.title)
        assertEquals("Contenido completo sobre time blocking.", timeBlocking.body)
    }

    @Test
    fun `refresh on a server error returns Error and preserves the previously cached articles`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(WIRE_JSON))
        repository.refresh()
        val cachedBefore = repository.getArticles()

        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        val result = repository.refresh()

        assertTrue(result is RefreshResult.Error)
        assertEquals(cachedBefore, repository.getArticles())
    }

    @Test
    fun `refresh when the socket fails returns Error and preserves the previously cached articles`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(WIRE_JSON))
        repository.refresh()
        val cachedBefore = repository.getArticles()

        // Simulates no connectivity / a dead server: the next call can't even open a connection.
        mockWebServer.shutdown()

        val result = repository.refresh()

        assertTrue(result is RefreshResult.Error)
        assertEquals(cachedBefore, repository.getArticles())
    }

    @Test
    fun `refresh on a malformed response body returns Error and preserves the previously cached articles`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(WIRE_JSON))
        repository.refresh()
        val cachedBefore = repository.getArticles()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("not valid json"))
        val result = repository.refresh()

        assertTrue(result is RefreshResult.Error)
        assertEquals(cachedBefore, repository.getArticles())
    }

    @Test
    fun `getArticleById reads from the cache - present returns the article, absent returns null`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(WIRE_JSON))
        repository.refresh()

        val found = repository.getArticleById("time-blocking")
        assertEquals("time-blocking", found?.id)
        assertEquals("Time blocking", found?.title)

        assertNull(repository.getArticleById("does-not-exist"))
    }
}
