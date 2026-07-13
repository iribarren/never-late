package com.neverlate.ui.articles

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.neverlate.R
import com.neverlate.data.articles.Article
import com.neverlate.ui.theme.NeverLateTheme
import org.junit.Rule
import org.junit.Test

/**
 * Drives [ArticleDetailBody] directly — the content-only half [ArticleDetailScreen] extracted in
 * feature 18b so the expanded-width two-pane [ArticlesListDetailPane]'s right pane could reuse the
 * exact same Content/NotFound rendering without a nested `Scaffold`/top bar/back arrow of its own
 * (see that composable's KDoc).
 *
 * [ArticleDetailScreenTest] already covers this same Content/NotFound rendering *through*
 * [ArticleDetailScreen] (with its `Scaffold` + back arrow); these tests instead pin down the two
 * things that are specific to the bare [ArticleDetailBody] extraction:
 *  - it renders the identical title/body or "not found" text with no `Scaffold` wrapper, so the
 *    two-pane detail pane never gets a second nested top bar, and
 *  - it never renders a back arrow of its own (there is no `onBack` parameter at all), which is
 *    exactly why the detail pane can sit next to the list pane with no back button of its own —
 *    the list stays visible alongside it.
 *
 * [ArticlesListDetailPane]'s own list pane (`ArticlesRoute`, defaulting to `hiltViewModel()`) and
 * its private `ArticleDetailPaneContent` helper are not exercised here: driving the full pane
 * would need Hilt test infrastructure (`HiltAndroidRule`/`HiltTestApplication`) this project does
 * not have set up for Compose UI tests (see [com.neverlate.ui.tasks.TasksRouteSnackbarTest]'s KDoc
 * for the same limitation elsewhere), and `ArticleDetailPaneContent` is file-private with no
 * overridable seam the way `ArticlesRoute`/`TasksRoute` expose an overridable `viewModel` param.
 */
class ArticleDetailBodyTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun notFoundMessage(): String = targetContext.getString(R.string.articles_not_found)

    private fun backContentDescription(): String =
        targetContext.getString(R.string.articles_back_content_description)

    private val pomodoro = Article(
        id = "pomodoro",
        title = "La técnica Pomodoro",
        summary = "Divide el trabajo en bloques cortos de 25 minutos.",
        body = "Cuerpo completo del artículo sobre Pomodoro.",
    )

    @Test
    fun contentState_showsTitleAndBody_withNoBackArrow() {
        composeTestRule.setContent {
            NeverLateTheme {
                ArticleDetailBody(uiState = ArticleDetailUiState.Content(pomodoro))
            }
        }

        composeTestRule.onNodeWithText(pomodoro.title).assertExists()
        composeTestRule.onNodeWithText(pomodoro.body).assertExists()
        // No Scaffold/TopAppBar around the bare body: nothing carries the back-arrow content
        // description that ArticleDetailScreen always renders.
        composeTestRule.onNodeWithContentDescription(backContentDescription()).assertDoesNotExist()
    }

    @Test
    fun notFoundState_showsNotFoundMessage_withNoBackArrow() {
        composeTestRule.setContent {
            NeverLateTheme {
                ArticleDetailBody(uiState = ArticleDetailUiState.NotFound)
            }
        }

        composeTestRule.onNodeWithText(notFoundMessage()).assertExists()
        composeTestRule.onNodeWithContentDescription(backContentDescription()).assertDoesNotExist()
    }

    @Test
    fun loadingState_rendersNothing() {
        composeTestRule.setContent {
            NeverLateTheme {
                ArticleDetailBody(uiState = ArticleDetailUiState.Loading)
            }
        }

        // Loading renders Unit (no content at all yet) — neither the previous nor a next state's
        // text should be present.
        composeTestRule.onNodeWithText(pomodoro.title).assertDoesNotExist()
        composeTestRule.onNodeWithText(notFoundMessage()).assertDoesNotExist()
    }
}
