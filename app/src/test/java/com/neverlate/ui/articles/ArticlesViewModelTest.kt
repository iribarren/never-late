package com.neverlate.ui.articles

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.testing.asSnapshot
import com.neverlate.data.articles.Article
import com.neverlate.data.articles.ArticleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * In-memory fake for [ArticleRepository]. [articlesPager] returns a **real** [Pager] flow over a
 * trivial single-page [PagingSource], rather than a bare `PagingData.from(items)`: `asSnapshot()`
 * drives a Pager the way the production [ArticlesRemoteMediator]-backed one is driven (it waits for
 * the load states a real [Pager] produces), whereas a static `PagingData.from()` piped through
 * [androidx.paging.cachedIn] never emits those settle signals and hangs the collector. This still
 * fakes away the network/[ArticlesRemoteMediator] entirely — that seam is
 * [com.neverlate.data.articles.ArticlesRemoteMediatorTest]'s job — and only proves
 * [ArticlesViewModel.articles] is correctly wired through `cachedIn` end to end.
 */
private class FakeArticleRepositoryForList(private val items: List<Article> = emptyList()) : ArticleRepository {

    override fun articlesPager(): Flow<PagingData<Article>> = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false),
        pagingSourceFactory = { SinglePagePagingSource(items) },
    ).flow

    override suspend fun getArticleById(id: String): Article? = items.firstOrNull { it.id == id }
}

/** Serves [items] as one already-complete page — the whole catalog, with no page after it. */
private class SinglePagePagingSource(private val items: List<Article>) : PagingSource<Int, Article>() {
    override fun getRefreshKey(state: PagingState<Int, Article>): Int? = null
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Article> =
        LoadResult.Page(data = items, prevKey = null, nextKey = null)
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

/**
 * Light smoke test: [ArticlesViewModel.articles] is a non-null [Flow] of [PagingData] that, once
 * collected, reflects whatever the repository's [ArticleRepository.articlesPager] produced — the
 * only thing this ViewModel is responsible for since feature 13c removed its hand-rolled
 * `ArticlesUiState`/`isRefreshing` (see its KDoc). Deliberately does not over-assert on Paging
 * internals (load states, `cachedIn`'s replay behavior across collectors, etc.) — those are Paging
 * library concerns, not this project's code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArticlesViewModelTest {

    // asSnapshot() + cachedIn(viewModelScope) is delicate to drive from a test: the cachedIn
    // multicast collector runs on Dispatchers.Main while asSnapshot() suspends until Paging settles.
    // Two things must both hold, or the snapshot hangs until runTest's watchdog fires:
    //  - the test body and Main must share ONE scheduler — achieved by passing this same dispatcher
    //    to runTest(testDispatcher) below (runTest adopts the dispatcher's scheduler), so there is a
    //    single clock instead of asSnapshot() and the cachedIn collector waiting on separate ones;
    //  - it must run eagerly (Unconfined, not Standard) so the cachedIn collector actually emits the
    //    PagingData without needing a manual advanceUntilIdle() that asSnapshot()'s wait never issues.
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `articles reflects the repository's pager content`() = runTest(testDispatcher) {
        val viewModel = ArticlesViewModel(FakeArticleRepositoryForList(listOf(pomodoro, timeBlocking)))

        val snapshot = viewModel.articles.asSnapshot()

        assertEquals(listOf(pomodoro, timeBlocking), snapshot)
    }

    @Test
    fun `an empty catalog produces an empty snapshot`() = runTest(testDispatcher) {
        val viewModel = ArticlesViewModel(FakeArticleRepositoryForList(emptyList()))

        val snapshot = viewModel.articles.asSnapshot()

        assertEquals(emptyList<Article>(), snapshot)
    }
}
