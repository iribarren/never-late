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
                    onArticleClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(emptyMessage()).assertExists()
    }

    @Test
    fun tappingBackButton_invokesOnBack() {
        var backCount = 0

        composeTestRule.setContent {
            NeverLateTheme {
                ArticlesScreen(
                    uiState = ArticlesUiState.Content(listOf(pomodoro)),
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
}
