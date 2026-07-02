package com.neverlate.ui.articles

import com.neverlate.data.articles.Article
import com.neverlate.data.articles.ArticleRepository
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
 * In-memory fake for [ArticleRepository], same shape as the one in [ArticlesViewModelTest]. Kept
 * as a separate private copy (rather than shared test code) to match this project's existing
 * one-fake-per-test-file convention (see [com.neverlate.ui.onboarding.OnboardingViewModelTest]).
 *
 * Named differently from the [ArticlesViewModelTest] fake: Kotlin top-level `private` only
 * restricts *access* to the declaring file, but the class name must still be unique within the
 * package, so two files can't both declare a top-level `private class FakeArticleRepository`.
 */
private class FakeArticleRepositoryForDetail(private val articles: List<Article>) : ArticleRepository {

    override suspend fun getArticles(): List<Article> = articles

    override suspend fun getArticleById(id: String): Article? = articles.firstOrNull { it.id == id }
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
    fun `valid id produces Content state with the matching article`() {
        val viewModel = ArticleDetailViewModel(repository, articleId = twoMinuteRule.id)

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ArticleDetailUiState.Content)
        assertEquals(twoMinuteRule, (state as ArticleDetailUiState.Content).article)
    }

    @Test
    fun `unknown id produces NotFound state`() {
        val viewModel = ArticleDetailViewModel(repository, articleId = "does-not-exist")

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is ArticleDetailUiState.NotFound)
    }

    @Test
    fun `empty repository produces NotFound state for any id`() {
        val viewModel = ArticleDetailViewModel(FakeArticleRepositoryForDetail(emptyList()), articleId = pomodoro.id)

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is ArticleDetailUiState.NotFound)
    }
}
