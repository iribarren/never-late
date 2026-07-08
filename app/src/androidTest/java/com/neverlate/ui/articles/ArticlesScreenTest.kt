package com.neverlate.ui.articles

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.neverlate.R
import com.neverlate.data.articles.Article
import com.neverlate.ui.theme.NeverLateTheme
import org.junit.Rule
import org.junit.Test

/**
 * Drives the stateless [ArticlesScreen] directly with hoisted state + callbacks (no real
 * [ArticlesViewModel] or repository involved), following the same pattern as
 * [com.neverlate.ui.onboarding.OnboardingScreenTest].
 */
class ArticlesScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun emptyMessage(): String = targetContext.getString(R.string.articles_empty)

    private fun errorMessage(): String = targetContext.getString(R.string.articles_error)

    private fun retryButtonText(): String = targetContext.getString(R.string.articles_retry)

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

    @Test
    fun content_rendersOneRowPerArticle_withTitleAndSummary() {
        composeTestRule.setContent {
            NeverLateTheme {
                ArticlesScreen(
                    uiState = ArticlesUiState.Content(listOf(pomodoro, timeBlocking)),
                    isRefreshing = false,
                    onRefresh = {},
                    onArticleClick = {},
                    onBack = {},
                )
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
                ArticlesScreen(
                    uiState = ArticlesUiState.Content(listOf(pomodoro, timeBlocking)),
                    isRefreshing = false,
                    onRefresh = {},
                    onArticleClick = { clickedId = it },
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(timeBlocking.title).performClick()

        assert(clickedId == timeBlocking.id) {
            "Expected onArticleClick to be invoked with \"${timeBlocking.id}\", got $clickedId"
        }
    }

    @Test
    fun emptyState_showsEmptyMessage() {
        composeTestRule.setContent {
            NeverLateTheme {
                ArticlesScreen(
                    uiState = ArticlesUiState.Empty,
                    isRefreshing = false,
                    onRefresh = {},
                    onArticleClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(emptyMessage()).assertExists()
    }

    @Test
    fun errorState_showsErrorMessageAndRetryButton() {
        composeTestRule.setContent {
            NeverLateTheme {
                ArticlesScreen(
                    uiState = ArticlesUiState.Error,
                    isRefreshing = false,
                    onRefresh = {},
                    onArticleClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(errorMessage()).assertExists()
        composeTestRule.onNodeWithText(retryButtonText()).assertExists()
    }

    @Test
    fun errorState_tappingRetryButton_invokesOnRefresh() {
        var refreshCount = 0

        composeTestRule.setContent {
            NeverLateTheme {
                ArticlesScreen(
                    uiState = ArticlesUiState.Error,
                    isRefreshing = false,
                    onRefresh = { refreshCount++ },
                    onArticleClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(retryButtonText()).performClick()

        assert(refreshCount == 1) { "Expected onRefresh to be invoked exactly once, was $refreshCount" }
    }

    @Test
    fun tappingBackButton_invokesOnBack() {
        var backCount = 0

        composeTestRule.setContent {
            NeverLateTheme {
                ArticlesScreen(
                    uiState = ArticlesUiState.Content(listOf(pomodoro)),
                    isRefreshing = false,
                    onRefresh = {},
                    onArticleClick = {},
                    onBack = { backCount++ },
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription(targetContext.getString(R.string.articles_back_content_description))
            .performClick()

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
                ArticlesScreen(
                    uiState = ArticlesUiState.Content(listOf(pomodoro)),
                    isRefreshing = false,
                    onRefresh = {},
                    onArticleClick = {},
                    onBack = null,
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription(targetContext.getString(R.string.articles_back_content_description))
            .assertDoesNotExist()
    }
}
