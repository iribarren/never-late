package com.neverlate.ui.articles

import com.neverlate.data.articles.Article
import com.neverlate.data.articles.ArticleRepository
import com.neverlate.data.articles.RefreshResult
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory fake for [ArticleRepository], same shape as the one in [ArticlesViewModelTest] (see
 * that file's KDoc for why [enqueueRefresh] exists). Kept as a separate private copy (rather than
 * shared test code) to match this project's existing one-fake-per-test-file convention (see
 * [com.neverlate.ui.onboarding.OnboardingViewModelTest]).
 *
 * Named differently from the [ArticlesViewModelTest] fake: Kotlin top-level `private` only
 * restricts *access* to the declaring file, but the class name must still be unique within the
 * package, so two files can't both declare a top-level `private class FakeArticleRepository`.
 */
private class FakeArticleRepositoryForDetail(initialCache: List<Article> = emptyList()) : ArticleRepository {

    var cache: List<Article> = initialCache
        private set

    private val queuedResults = ArrayDeque<RefreshResult>()
    private val queuedCacheUpdates = ArrayDeque<List<Article>?>()

    var refreshCallCount = 0
        private set

    /**
     * Queues the [RefreshResult] the *next* call to [refresh] will return, and optionally what
     * [cache] should hold immediately afterwards (simulating a successful network write). Leave
     * [newCache] `null` to simulate a refresh that doesn't change the cache.
     */
    fun enqueueRefresh(result: RefreshResult, newCache: List<Article>? = null) {
        queuedResults.addLast(result)
        queuedCacheUpdates.addLast(newCache)
    }

    override suspend fun getArticles(): List<Article> = cache

    override suspend fun getArticleById(id: String): Article? = cache.firstOrNull { it.id == id }

    override suspend fun refresh(): RefreshResult {
        refreshCallCount++
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

private val twoMinuteRule = Article(
    id = "two-minute-rule",
    title = "La regla de los 2 minutos",
    summary = "Si una tarea lleva menos de 2 minutos, hazla ya.",
    body = "Cuerpo completo del artículo sobre la regla de los 2 minutos.",
)

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = FakeArticleRepositoryForDetail(listOf(pomodoro, twoMinuteRule))

    @Before
    fun setUp() {
        // ArticleDetailViewModel.init launches on viewModelScope (Dispatchers.Main); same pattern
        // as ArticlesViewModelTest so the test controls when that coroutine runs.
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Loading before the repository call completes`() {
        val viewModel = ArticleDetailViewModel(repository, articleId = pomodoro.id)

        assertTrue(viewModel.uiState.value is ArticleDetailUiState.Loading)
    }

    @Test
    fun `valid id already in the cache produces Content without needing a refresh`() {
        val viewModel = ArticleDetailViewModel(repository, articleId = twoMinuteRule.id)

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ArticleDetailUiState.Content)
        assertEquals(twoMinuteRule, (state as ArticleDetailUiState.Content).article)
        assertEquals(0, repository.refreshCallCount)
    }

    @Test
    fun `unknown id with a successful refresh that still finds nothing produces NotFound`() {
        val viewModel = ArticleDetailViewModel(repository, articleId = "does-not-exist")

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is ArticleDetailUiState.NotFound)
    }

    @Test
    fun `empty cache produces NotFound for any id once the refresh succeeds without finding it`() {
        val viewModel = ArticleDetailViewModel(FakeArticleRepositoryForDetail(emptyList()), articleId = pomodoro.id)

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is ArticleDetailUiState.NotFound)
    }

    @Test
    fun `absent id with a failed refresh produces Error instead of NotFound`() {
        val repo = FakeArticleRepositoryForDetail(emptyList())
        repo.enqueueRefresh(RefreshResult.Error(IOException("no network")))

        val viewModel = ArticleDetailViewModel(repo, articleId = "does-not-exist")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is ArticleDetailUiState.Error)
    }

    @Test
    fun `retry after Error can recover to Content once the article becomes available`() {
        val repo = FakeArticleRepositoryForDetail(emptyList())
        repo.enqueueRefresh(RefreshResult.Error(IOException("no network")))

        val viewModel = ArticleDetailViewModel(repo, articleId = pomodoro.id)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value is ArticleDetailUiState.Error)

        repo.enqueueRefresh(RefreshResult.Success, newCache = listOf(pomodoro))
        viewModel.retry()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ArticleDetailUiState.Content)
        assertEquals(pomodoro, (state as ArticleDetailUiState.Content).article)
        assertEquals(2, repo.refreshCallCount)
    }
}
