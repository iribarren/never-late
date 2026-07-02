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
 * In-memory fake for [ArticleRepository]. Serves a canned list from memory so tests never touch
 * `app/src/main/assets/articles.json` or need an Android [android.content.Context] (which
 * [com.neverlate.data.articles.LocalArticleRepository] requires and a plain JVM unit test
 * cannot provide).
 */
private class FakeArticleRepository(private val articles: List<Article>) : ArticleRepository {

    override suspend fun getArticles(): List<Article> = articles

    override suspend fun getArticleById(id: String): Article? = articles.firstOrNull { it.id == id }
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
    fun `repository with articles produces Content state with all articles`() {
        val viewModel = ArticlesViewModel(FakeArticleRepository(listOf(pomodoro, timeBlocking)))

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ArticlesUiState.Content)
        assertEquals(listOf(pomodoro, timeBlocking), (state as ArticlesUiState.Content).articles)
    }

    @Test
    fun `empty repository produces Empty state`() {
        val viewModel = ArticlesViewModel(FakeArticleRepository(emptyList()))

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is ArticlesUiState.Empty)
    }
}
