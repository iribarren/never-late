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
 * Drives the stateless [ArticleDetailScreen] directly with hoisted state + callbacks (no real
 * [ArticleDetailViewModel] or repository involved), following the same pattern as
 * [ArticlesScreenTest] and [com.neverlate.ui.onboarding.OnboardingScreenTest].
 */
class ArticleDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun notFoundMessage(): String = targetContext.getString(R.string.articles_not_found)

    private fun errorMessage(): String = targetContext.getString(R.string.articles_error)

    private fun retryButtonText(): String = targetContext.getString(R.string.articles_retry)

    private fun backContentDescription(): String =
        targetContext.getString(R.string.articles_back_content_description)

    private val pomodoro = Article(
        id = "pomodoro",
        title = "La técnica Pomodoro",
        summary = "Divide el trabajo en bloques cortos de 25 minutos.",
        body = "Cuerpo completo del artículo sobre Pomodoro.",
    )

    @Test
    fun content_showsTitleAndBody() {
        composeTestRule.setContent {
            NeverLateTheme {
                ArticleDetailScreen(
                    uiState = ArticleDetailUiState.Content(pomodoro),
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(pomodoro.title).assertExists()
        composeTestRule.onNodeWithText(pomodoro.body).assertExists()
    }

    @Test
    fun notFoundState_showsNotFoundMessage() {
        composeTestRule.setContent {
            NeverLateTheme {
                ArticleDetailScreen(
                    uiState = ArticleDetailUiState.NotFound,
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(notFoundMessage()).assertExists()
    }

    @Test
    fun errorState_showsErrorMessageAndRetryButton() {
        composeTestRule.setContent {
            NeverLateTheme {
                ArticleDetailScreen(
                    uiState = ArticleDetailUiState.Error,
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(errorMessage()).assertExists()
        composeTestRule.onNodeWithText(retryButtonText()).assertExists()
    }

    @Test
    fun errorState_tappingRetryButton_invokesOnRetry() {
        var retryCount = 0

        composeTestRule.setContent {
            NeverLateTheme {
                ArticleDetailScreen(
                    uiState = ArticleDetailUiState.Error,
                    onRetry = { retryCount++ },
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(retryButtonText()).performClick()

        assert(retryCount == 1) { "Expected onRetry to be invoked exactly once, was $retryCount" }
    }

    @Test
    fun tappingBackButton_invokesOnBack() {
        var backCount = 0

        composeTestRule.setContent {
            NeverLateTheme {
                ArticleDetailScreen(
                    uiState = ArticleDetailUiState.Content(pomodoro),
                    onRetry = {},
                    onBack = { backCount++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(backContentDescription()).performClick()

        assert(backCount == 1) { "Expected onBack to be invoked exactly once, was $backCount" }
    }
}
