package com.neverlate.ui.articles

import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import com.neverlate.data.articles.Article
import com.neverlate.data.articles.ArticleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory fake for [ArticleRepository]. Feature 13c removed `refresh()`/`RefreshResult` from the
 * interface (see [ArticleRepository]'s KDoc), so [ArticleDetailViewModel] now only ever calls
 * [getArticleById] — [articlesPager] is never exercised by these tests, but must still be
 * implemented to satisfy the interface; an empty [PagingData] stream is enough of a stub.
 */
private class FakeArticleRepositoryForDetail(private val cache: List<Article> = emptyList()) : ArticleRepository {

    override fun articlesPager(): Flow<PagingData<Article>> = flowOf(PagingData.empty())

    override suspend fun getArticleById(id: String): Article? = cache.firstOrNull { it.id == id }
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

/**
 * Feature 13d: [ArticleDetailViewModel] now reads its `articleId` navigation argument from a
 * [SavedStateHandle] instead of a plain constructor parameter — this builds one pre-seeded with
 * the same key ("articleId") `AppNavHost`'s `navArgument` declares, exactly as `hiltViewModel()`
 * would when reached through real navigation.
 */
private fun savedStateHandleFor(articleId: String) = SavedStateHandle(mapOf("articleId" to articleId))

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = FakeArticleRepositoryForDetail(listOf(pomodoro, twoMinuteRule))

    @Before
    fun setUp() {
        // ArticleDetailViewModel.init launches on viewModelScope (Dispatchers.Main); StandardTestDispatcher
        // + setMain lets the test control exactly when that coroutine runs.
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Loading before the repository call completes`() {
        val viewModel = ArticleDetailViewModel(repository, savedStateHandleFor(pomodoro.id))

        assertTrue(viewModel.uiState.value is ArticleDetailUiState.Loading)
    }

    @Test
    fun `valid id already in the cache produces Content`() {
        val viewModel = ArticleDetailViewModel(repository, savedStateHandleFor(twoMinuteRule.id))

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ArticleDetailUiState.Content)
        assertEquals(twoMinuteRule, (state as ArticleDetailUiState.Content).article)
    }

    @Test
    fun `unknown id produces NotFound`() {
        val viewModel = ArticleDetailViewModel(repository, savedStateHandleFor("does-not-exist"))

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is ArticleDetailUiState.NotFound)
    }

    @Test
    fun `empty cache produces NotFound for any id`() {
        val viewModel = ArticleDetailViewModel(FakeArticleRepositoryForDetail(emptyList()), savedStateHandleFor(pomodoro.id))

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is ArticleDetailUiState.NotFound)
    }
}
