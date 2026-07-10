package com.neverlate.ui.articles

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.platform.app.InstrumentationRegistry
import com.neverlate.R
import com.neverlate.data.articles.Article
import com.neverlate.ui.theme.NeverLateTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

/**
 * Drives the stateless [ArticlesScreen] directly, feeding a hand-crafted `Flow<PagingData<Article>>`
 * through `collectAsLazyPagingItems()` inside the test (same [flowOf]/[PagingData.from] trick this
 * file's own `@Preview`s use) rather than a real [ArticlesViewModel]/repository/[MockWebServer] —
 * no [androidx.paging.RemoteMediator] involved, so each test only has to construct the
 * [LoadStates] combination it wants to assert on.
 *
 * Feature 13c retired the `onRefresh`/`uiState` callback-driven version of this test (see git
 * history): [ArticlesScreen] now drives everything off `LazyPagingItems.loadState`, and pull-to-
 * refresh/retry call `articles.refresh()`/`articles.retry()` directly on the collected
 * [androidx.paging.compose.LazyPagingItems] rather than through a callback prop — there is no seam
 * left to intercept "was retry requested" without a fully fake, controllable
 * [androidx.paging.PagingSource] (disproportionate for this screen), so these tests assert on
 * *rendered state* (spinner/error/retry-button presence, touch target size) rather than on refresh/
 * retry being wired through to another call.
 */
class ArticlesScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun emptyMessage(): String = targetContext.getString(R.string.articles_empty)

    private fun errorMessage(): String = targetContext.getString(R.string.articles_error)

    private fun retryButtonText(): String = targetContext.getString(R.string.articles_retry)

    private fun appendErrorMessage(): String = targetContext.getString(R.string.articles_append_error)

    private fun loadingMoreDescription(): String = targetContext.getString(R.string.articles_loading_more_content_description)

    private fun backContentDescription(): String = targetContext.getString(R.string.articles_back_content_description)

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

    private val notLoadingStates = LoadStates(
        refresh = LoadState.NotLoading(endOfPaginationReached = false),
        prepend = LoadState.NotLoading(endOfPaginationReached = true),
        append = LoadState.NotLoading(endOfPaginationReached = false),
    )

    @Test
    fun content_rendersOneRowPerArticle_withTitleAndSummary() {
        composeTestRule.setContent {
            NeverLateTheme {
                val articles = flowOf(PagingData.from(listOf(pomodoro, timeBlocking))).collectAsLazyPagingItems()
                ArticlesScreen(articles = articles, onArticleClick = {}, onBack = {})
            }
        }

        composeTestRule.onNodeWithText(pomodoro.title).assertExists()
        composeTestRule.onNodeWithText(pomodoro.summary).assertExists()
        composeTestRule.onNodeWithText(timeBlocking.title).assertExists()
        composeTestRule.onNodeWithText(timeBlocking.summary).assertExists()
    }

    @Test
    fun tappingArticleRow_invokesOnArticleClick_withThatArticlesId() {
        var clickedId: String? = null

        composeTestRule.setContent {
            NeverLateTheme {
                val articles = flowOf(PagingData.from(listOf(pomodoro, timeBlocking))).collectAsLazyPagingItems()
                ArticlesScreen(articles = articles, onArticleClick = { clickedId = it }, onBack = {})
            }
        }

        composeTestRule.onNodeWithText(timeBlocking.title).performClick()

        assert(clickedId == timeBlocking.id) {
            "Expected onArticleClick to be invoked with \"${timeBlocking.id}\", got $clickedId"
        }
    }

    @Test
    fun emptyState_loadedWithZeroItems_showsEmptyMessage() {
        composeTestRule.setContent {
            NeverLateTheme {
                val articles = flowOf(PagingData.empty<Article>(sourceLoadStates = notLoadingStates)).collectAsLazyPagingItems()
                ArticlesScreen(articles = articles, onArticleClick = {}, onBack = {})
            }
        }

        composeTestRule.onNodeWithText(emptyMessage()).assertExists()
    }

    @Test
    fun fullScreenError_refreshErrorWithNoCachedItems_showsErrorMessageAndRetryButton() {
        val errorLoadStates = LoadStates(
            refresh = LoadState.Error(Throwable("boom")),
            prepend = LoadState.NotLoading(endOfPaginationReached = true),
            append = LoadState.NotLoading(endOfPaginationReached = true),
        )

        composeTestRule.setContent {
            NeverLateTheme {
                val articles = flowOf(PagingData.empty<Article>(sourceLoadStates = errorLoadStates)).collectAsLazyPagingItems()
                ArticlesScreen(articles = articles, onArticleClick = {}, onBack = {})
            }
        }

        composeTestRule.onNodeWithText(errorMessage()).assertExists()
        composeTestRule.onNodeWithText(retryButtonText()).apply {
            assertExists()
            // Feature 18's 48dp touch-target guideline, applied to MessageState's action button.
            assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun appendLoading_showsLoadingMoreSpinner_belowTheLoadedRows() {
        val appendLoadingStates = LoadStates(
            refresh = LoadState.NotLoading(endOfPaginationReached = false),
            prepend = LoadState.NotLoading(endOfPaginationReached = true),
            append = LoadState.Loading,
        )

        composeTestRule.setContent {
            NeverLateTheme {
                val articles = flowOf(PagingData.from(listOf(pomodoro), sourceLoadStates = appendLoadingStates)).collectAsLazyPagingItems()
                ArticlesScreen(articles = articles, onArticleClick = {}, onBack = {})
            }
        }

        composeTestRule.onNodeWithText(pomodoro.title).assertExists()
        composeTestRule.onNodeWithContentDescription(loadingMoreDescription()).assertExists()
    }

    @Test
    fun appendError_showsInlineRetryRow_belowTheLoadedRows_withAtLeast48dpTouchTarget() {
        val appendErrorStates = LoadStates(
            refresh = LoadState.NotLoading(endOfPaginationReached = false),
            prepend = LoadState.NotLoading(endOfPaginationReached = true),
            append = LoadState.Error(Throwable("boom")),
        )

        composeTestRule.setContent {
            NeverLateTheme {
                val articles = flowOf(PagingData.from(listOf(pomodoro), sourceLoadStates = appendErrorStates)).collectAsLazyPagingItems()
                ArticlesScreen(articles = articles, onArticleClick = {}, onBack = {})
            }
        }

        composeTestRule.onNodeWithText(pomodoro.title).assertExists()
        composeTestRule.onNodeWithText(appendErrorMessage()).assertExists()
        composeTestRule.onNodeWithText(retryButtonText()).apply {
            assertExists()
            assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun tappingBackButton_invokesOnBack() {
        var backCount = 0

        composeTestRule.setContent {
            NeverLateTheme {
                val articles = flowOf(PagingData.from(listOf(pomodoro))).collectAsLazyPagingItems()
                ArticlesScreen(articles = articles, onArticleClick = {}, onBack = { backCount++ })
            }
        }

        composeTestRule.onNodeWithContentDescription(backContentDescription()).performClick()

        assert(backCount == 1) { "Expected onBack to be invoked exactly once, was $backCount" }
    }

    /**
     * Feature 18: as a top-level bottom-bar tab, Articles is reached laterally, so [onBack] is
     * `null` and no back arrow is shown (the pushed/secondary case is covered by
     * [tappingBackButton_invokesOnBack] above).
     */
    @Test
    fun topLevelUsage_onBackNull_hidesBackArrow() {
        composeTestRule.setContent {
            NeverLateTheme {
                val articles = flowOf(PagingData.from(listOf(pomodoro))).collectAsLazyPagingItems()
                ArticlesScreen(articles = articles, onArticleClick = {}, onBack = null)
            }
        }

        composeTestRule.onNodeWithContentDescription(backContentDescription()).assertDoesNotExist()
    }
}
