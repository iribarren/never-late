package com.neverlate.data.articles

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import com.neverlate.data.sync.buildInMemoryTestDatabase
import com.neverlate.data.tasks.NeverLateDatabase
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
 * Tests [ArticlesRemoteMediator] — the network half of feature 13c's Paging 3 integration (see its
 * KDoc for the REFRESH/APPEND/PREPEND contract this exercises). Uses a real [ArticlesApi] (built
 * exactly the way [com.neverlate.MainActivity] builds it, via [ArticlesNetwork.create]) talking to
 * a local [MockWebServer], paired with a real, Robolectric-backed in-memory [NeverLateDatabase]
 * ([buildInMemoryTestDatabase], reused from feature 11's sync tests) — a hand-written DAO fake
 * would not exercise `database.withTransaction`, which is exactly what REFRESH's "clear + repopulate
 * atomically" behavior needs verified.
 */
@OptIn(ExperimentalPagingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArticlesRemoteMediatorTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var database: NeverLateDatabase
    private lateinit var mediator: ArticlesRemoteMediator

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        database = buildInMemoryTestDatabase()
        val api = ArticlesNetwork.create(baseUrl = mockWebServer.url("/").toString())
        mediator = ArticlesRemoteMediator(api, database)
    }

    @After
    fun tearDown() {
        database.close()
        try {
            mockWebServer.shutdown()
        } catch (error: Exception) {
            // A test that simulates a dead server already shut it down; nothing left to clean up.
        }
    }

    /** Builds the wire-format `ArticlesPageDto` JSON body for a page of [count] articles, starting at [firstIndex]. */
    private fun pageJson(count: Int, size: Int, total: Int, page: Int, firstIndex: Int = 0): String {
        val items = (firstIndex until firstIndex + count).joinToString(",") { i ->
            """{"article_id":"a$i","title":"Title $i","content":"Content $i."}"""
        }
        return """{"items":[$items],"page":$page,"size":$size,"total":$total}"""
    }

    /** A minimal [PagingState] with no loaded pages — REFRESH and an empty-state APPEND/PREPEND both start from this. */
    private fun emptyState(pageSize: Int = 2): PagingState<Int, ArticleEntity> = PagingState(
        pages = emptyList(),
        anchorPosition = null,
        config = PagingConfig(pageSize = pageSize),
        leadingPlaceholderCount = 0,
    )

    /** A [PagingState] whose last loaded page ends with [lastItem] — what APPEND reads via `lastItemOrNull()`. */
    private fun stateEndingWith(lastItem: ArticleEntity, pageSize: Int = 2): PagingState<Int, ArticleEntity> = PagingState(
        pages = listOf(PagingSource.LoadResult.Page(data = listOf(lastItem), prevKey = null, nextKey = null)),
        anchorPosition = null,
        config = PagingConfig(pageSize = pageSize),
        leadingPlaceholderCount = 0,
    )

    // REFRESH --------------------------------------------------------------------------------------

    @Test
    fun `REFRESH loads page 0 and reports more pages when a full page comes back`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(pageJson(count = 2, size = 2, total = 5, page = 0)))

        val result = mediator.load(LoadType.REFRESH, emptyState(pageSize = 2))

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)

        val request = mockWebServer.takeRequest()
        assertTrue(request.path!!.contains("page=0"))
    }

    @Test
    fun `REFRESH reports end of pagination when the server returns a short page`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(pageJson(count = 1, size = 2, total = 1, page = 0)))

        val result = mediator.load(LoadType.REFRESH, emptyState(pageSize = 2))

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
    }

    @Test
    fun `REFRESH clears the previous cache and remote keys before repopulating, assigning remoteOrder by global index`() = runTest {
        // Seed a stale article (from a session before the user pulled to refresh) plus its remote key.
        database.articleDao().upsertAll(listOf(ArticleEntity(id = "stale", title = "Stale", summary = "s", body = "b", remoteOrder = 99)))
        database.articleRemoteKeysDao().insertAll(listOf(ArticleRemoteKeys(articleId = "stale", prevKey = null, nextKey = null)))

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(pageJson(count = 2, size = 2, total = 2, page = 0)))
        mediator.load(LoadType.REFRESH, emptyState(pageSize = 2))

        assertNull("the stale row must not survive a REFRESH", database.articleDao().getById("stale"))
        assertNull("the stale row's remote key must not survive a REFRESH", database.articleRemoteKeysDao().remoteKeysByArticleId("stale"))

        val first = database.articleDao().getById("a0")!!
        val second = database.articleDao().getById("a1")!!
        assertEquals(0, first.remoteOrder)
        assertEquals(1, second.remoteOrder)

        // page 0 was a full page (2 == 2, so end reached, since total (2) leaves nothing after it)
        // but with a short page (count < size) nextKey is null - here it's a full page against size
        // 2 with 2 items, so endOfPaginationReached = (2 < 2) = false, meaning a nextKey was stored.
        val remoteKey = database.articleRemoteKeysDao().remoteKeysByArticleId("a0")!!
        assertNull("page 0 has no page before it", remoteKey.prevKey)
        assertEquals(1, remoteKey.nextKey)
    }

    // APPEND ---------------------------------------------------------------------------------------

    @Test
    fun `APPEND with no loaded items reports end of pagination without hitting the network`() = runTest {
        val result = mediator.load(LoadType.APPEND, emptyState())

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun `APPEND advances to the next page using the last item's stored remote key`() = runTest {
        val lastItem = ArticleEntity(id = "a1", title = "Title 1", summary = "s", body = "b", remoteOrder = 1)
        database.articleDao().upsertAll(listOf(lastItem))
        database.articleRemoteKeysDao().insertAll(listOf(ArticleRemoteKeys(articleId = "a1", prevKey = 0, nextKey = 1)))

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(pageJson(count = 2, size = 2, total = 4, page = 1, firstIndex = 2)))

        val result = mediator.load(LoadType.APPEND, stateEndingWith(lastItem, pageSize = 2))

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)

        val request = mockWebServer.takeRequest()
        assertTrue(request.path!!.contains("page=1"))

        // The new page's articles are added, not clearing what was already cached (unlike REFRESH).
        assertEquals("Title 1", database.articleDao().getById("a1")!!.title)
        val appended = database.articleDao().getById("a2")!!
        assertEquals(2, appended.remoteOrder)
    }

    @Test
    fun `APPEND with no next key recorded reports end of pagination without hitting the network`() = runTest {
        val lastItem = ArticleEntity(id = "a1", title = "Title 1", summary = "s", body = "b", remoteOrder = 1)
        database.articleDao().upsertAll(listOf(lastItem))
        database.articleRemoteKeysDao().insertAll(listOf(ArticleRemoteKeys(articleId = "a1", prevKey = 0, nextKey = null)))

        val result = mediator.load(LoadType.APPEND, stateEndingWith(lastItem, pageSize = 2))

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun `APPEND that fetches a short last page reports end of pagination`() = runTest {
        val lastItem = ArticleEntity(id = "a1", title = "Title 1", summary = "s", body = "b", remoteOrder = 1)
        database.articleDao().upsertAll(listOf(lastItem))
        database.articleRemoteKeysDao().insertAll(listOf(ArticleRemoteKeys(articleId = "a1", prevKey = 0, nextKey = 1)))

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(pageJson(count = 1, size = 2, total = 3, page = 1, firstIndex = 2)))

        val result = mediator.load(LoadType.APPEND, stateEndingWith(lastItem, pageSize = 2))

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertNull(database.articleRemoteKeysDao().remoteKeysByArticleId("a2")!!.nextKey)
    }

    // PREPEND ----------------------------------------------------------------------------------------

    @Test
    fun `PREPEND always reports end of pagination without hitting the network`() = runTest {
        val result = mediator.load(LoadType.PREPEND, emptyState())

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(0, mockWebServer.requestCount)
    }

    // Failure paths -----------------------------------------------------------------------------------

    @Test
    fun `a dead connection surfaces as MediatorResult Error`() = runTest {
        mockWebServer.shutdown()

        val result = mediator.load(LoadType.REFRESH, emptyState())

        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertTrue((result as RemoteMediator.MediatorResult.Error).throwable is java.io.IOException)
    }

    @Test
    fun `a non-2xx response surfaces as MediatorResult Error`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val result = mediator.load(LoadType.REFRESH, emptyState())

        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertTrue((result as RemoteMediator.MediatorResult.Error).throwable is retrofit2.HttpException)
    }

    @Test
    fun `a malformed response body surfaces as MediatorResult Error`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("not valid json"))

        val result = mediator.load(LoadType.REFRESH, emptyState())

        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertTrue((result as RemoteMediator.MediatorResult.Error).throwable is kotlinx.serialization.SerializationException)
    }

    @Test
    fun `a failed REFRESH does not clear the previously cached articles`() = runTest {
        database.articleDao().upsertAll(listOf(ArticleEntity(id = "cached", title = "Cached", summary = "s", body = "b")))

        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mediator.load(LoadType.REFRESH, emptyState())

        assertEquals("Cached", database.articleDao().getById("cached")!!.title)
    }
}
