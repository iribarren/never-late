package com.neverlate.data.articles

import androidx.paging.testing.asSnapshot
import com.neverlate.data.sync.buildInMemoryTestDatabase
import com.neverlate.data.tasks.NeverLateDatabase
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration-style test for [CachingArticleRepository]: a real [ArticlesApi] (see
 * [ArticlesRemoteMediatorTest]'s KDoc for why a real Retrofit + [MockWebServer] pair is this
 * project's convention here) plus a real, Robolectric-backed in-memory [NeverLateDatabase]
 * ([buildInMemoryTestDatabase]).
 *
 * Feature 13c retired the old `getArticles()`/`refresh()` pair this class used to expose (see its
 * KDoc) in favour of one continuous [androidx.paging.PagingData] stream — [asSnapshot] (from
 * `androidx.paging:paging-testing`) is Paging's own way to turn that stream back into a plain
 * `List<Article>` inside a coroutine test, driving the underlying [Pager][androidx.paging.Pager] +
 * [ArticlesRemoteMediator] exactly like a real UI collector would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CachingArticleRepositoryTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var database: NeverLateDatabase
    private lateinit var repository: CachingArticleRepository

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        database = buildInMemoryTestDatabase()
        val api = ArticlesNetwork.create(baseUrl = mockWebServer.url("/").toString())
        repository = CachingArticleRepository(api = api, database = database)
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

    @Test
    fun `articlesPager emits the mapped domain articles once the initial REFRESH lands`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[
                    {"article_id":"pomodoro","title":"La técnica Pomodoro","content":"Divide el trabajo en bloques."},
                    {"article_id":"time-blocking","title":"Time blocking","content":"Asigna cada hora a una tarea."}
                ],"page":0,"size":20,"total":2}""",
            ),
        )

        val snapshot = repository.articlesPager().asSnapshot()

        assertEquals(2, snapshot.size)
        val pomodoro = snapshot.first { it.id == "pomodoro" }
        assertEquals("La técnica Pomodoro", pomodoro.title)
        assertEquals("Divide el trabajo en bloques.", pomodoro.body)
    }

    @Test
    fun `getArticleById reads from the Room cache directly - present returns the article, absent returns null`() = runTest {
        database.articleDao().upsertAll(
            listOf(ArticleEntity(id = "time-blocking", title = "Time blocking", summary = "Asigna horas.", body = "Cuerpo completo.")),
        )

        val found = repository.getArticleById("time-blocking")
        assertEquals("time-blocking", found?.id)
        assertEquals("Time blocking", found?.title)

        assertNull(repository.getArticleById("does-not-exist"))
    }
}
