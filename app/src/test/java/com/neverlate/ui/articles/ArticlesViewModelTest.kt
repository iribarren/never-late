package com.neverlate.ui.articles

import com.neverlate.data.articles.Article
import com.neverlate.data.articles.ArticleRepository
import com.neverlate.data.articles.RefreshResult
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory fake for [ArticleRepository]. [cache] stands in for what Room currently holds;
 * [enqueueRefresh] lets a test script exactly what the *next* call to [refresh] should report and,
 * optionally, what the cache should look like immediately afterwards (simulating a network write)
 * — which is what makes it possible to drive [ArticlesViewModel]'s stale-while-revalidate paths
 * (offline-with-cache, empty, error, retry) from a plain JVM test, with no real network or Room.
 *
 * [refresh] always waits on a tiny [delay] before resolving, purely so a test can observe
 * [ArticlesViewModel.isRefreshing] flip to `true` mid-flight (see the `isRefreshing` test below):
 * with no suspension point at all inside [refresh], draining the test dispatcher's queue up to the
 * current virtual time would run the whole coroutine to completion in one step, leaving nothing to
 * observe in between.
 */
private class FakeArticleRepository(initialCache: List<Article> = emptyList()) : ArticleRepository {

    var cache: List<Article> = initialCache
        private set

    private val queuedResults = ArrayDeque<RefreshResult>()
    private val queuedCacheUpdates = ArrayDeque<List<Article>?>()

    var refreshCallCount = 0
        private set

    /**
     * Queues the [RefreshResult] the *next* call to [refresh] will return. [newCache], if
     * non-null, becomes [cache]'s new content once that refresh "completes" — standing in for a
     * successful network fetch being written to Room. Leave it `null` to simulate a refresh that
     * doesn't change the cache (a failure, or a success that found nothing new).
     */
    fun enqueueRefresh(result: RefreshResult, newCache: List<Article>? = null) {
        queuedResults.addLast(result)
        queuedCacheUpdates.addLast(newCache)
    }

    override suspend fun getArticles(): List<Article> = cache

    override suspend fun getArticleById(id: String): Article? = cache.firstOrNull { it.id == id }

    override suspend fun refresh(): RefreshResult {
        refreshCallCount++
        delay(1)
        val result = if (queuedResults.isNotEmpty()) queuedResults.removeFirst() else RefreshResult.Success
        val newCache = if (queuedCacheUpdates.isNotEmpty()) queuedCacheUpdates.removeFirst() else null
        if (newCache != null) cache = newCache
        return result
    }
}

private val pomodoro = Article(
    id = "pomodoro",
    title = "La técnica Pomodoro",
    summary = "Divide el trabajo en bloques cortos de 25 minutos.",
    body = "Cuerpo completo del artículo sobre Pomodoro.",
)

private val timeBlocking = Article(
    id = "time-blocking",
    title = "Time blocking",
    summary = "Asigna cada hora del día a una tarea concreta.",
    body = "Cuerpo completo del artículo sobre time-blocking.",
)

@OptIn(ExperimentalCoroutinesApi::class)
class ArticlesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // ArticlesViewModel.init launches on viewModelScope (Dispatchers.Main); StandardTestDispatcher
        // + setMain lets the test control exactly when that coroutine runs.
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Loading before the repository call completes`() {
        val viewModel = ArticlesViewModel(FakeArticleRepository(listOf(pomodoro)))

        // No advanceUntilIdle() yet: the init{} coroutine is queued on testDispatcher but hasn't run.
        assertTrue(viewModel.uiState.value is ArticlesUiState.Loading)
    }

    @Test
    fun `cached articles are shown as Content after a refresh that finds no changes`() {
        val viewModel = ArticlesViewModel(FakeArticleRepository(listOf(pomodoro, timeBlocking)))

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ArticlesUiState.Content)
        assertEquals(listOf(pomodoro, timeBlocking), (state as ArticlesUiState.Content).articles)
    }

    @Test
    fun `empty cache with a successful refresh that finds nothing produces Empty state`() {
        val viewModel = ArticlesViewModel(FakeArticleRepository(emptyList()))

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is ArticlesUiState.Empty)
    }

    @Test
    fun `offline with cache - a failed refresh keeps showing the cached content instead of Error`() {
        val repository = FakeArticleRepository(listOf(pomodoro, timeBlocking))
        repository.enqueueRefresh(RefreshResult.Error(IOException("no network")))

        val viewModel = ArticlesViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ArticlesUiState.Content)
        assertEquals(listOf(pomodoro, timeBlocking), (state as ArticlesUiState.Content).articles)
    }

    @Test
    fun `empty cache with a failed refresh produces Error state`() {
        val repository = FakeArticleRepository(emptyList())
        repository.enqueueRefresh(RefreshResult.Error(IOException("no network")))

        val viewModel = ArticlesViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is ArticlesUiState.Error)
    }

    @Test
    fun `calling refresh after an Error retries and can recover to Content`() {
        val repository = FakeArticleRepository(emptyList())
        repository.enqueueRefresh(RefreshResult.Error(IOException("no network")))

        val viewModel = ArticlesViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value is ArticlesUiState.Error)

        repository.enqueueRefresh(RefreshResult.Success, newCache = listOf(pomodoro))
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ArticlesUiState.Content)
        assertEquals(listOf(pomodoro), (state as ArticlesUiState.Content).articles)
        assertEquals(2, repository.refreshCallCount)
    }

    @Test
    fun `isRefreshing is true only while a refresh is in flight`() {
        val viewModel = ArticlesViewModel(FakeArticleRepository(listOf(pomodoro)))
        assertFalse(viewModel.isRefreshing.value)

        // Runs everything scheduled up to (but not past) the fake's delay(1) inside refresh() —
        // enough to observe isRefreshing flip to true mid-flight, without finishing the refresh.
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.isRefreshing.value)

        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.isRefreshing.value)
    }
}
