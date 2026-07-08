package com.neverlate.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.neverlate.R
import com.neverlate.ui.theme.NeverLateTheme
import org.junit.Rule
import org.junit.Test

/**
 * Drives the stateless [HomeScreen] directly with hoisted state + callbacks, same pattern as
 * [com.neverlate.ui.tasks.TasksScreenTest] / [com.neverlate.ui.settings.SettingsScreenTest].
 * Feature 15 turned the two Home rows into rich `ListItem`s (icon + label + description); these
 * tests guard that the new supporting descriptions are actually shown and that wrapping the rows
 * in a `Card` didn't change navigation behaviour.
 */
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(resId: Int): String = targetContext.getString(resId)

    @Test
    fun content_showsOptionLabelsAndDescriptions() {
        composeTestRule.setContent {
            NeverLateTheme {
                HomeScreen(
                    uiState = HomeUiState(name = "Ada"),
                    onArticlesClick = {},
                    onTasksClick = {},
                    onSettingsClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.home_option_tasks)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.home_option_tasks_description)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.home_option_articles)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.home_option_articles_description)).assertIsDisplayed()
    }

    @Test
    fun tappingTasksRow_invokesOnTasksClick() {
        var tasksClicked = false
        var articlesClicked = false

        composeTestRule.setContent {
            NeverLateTheme {
                HomeScreen(
                    uiState = HomeUiState(name = "Ada"),
                    onArticlesClick = { articlesClicked = true },
                    onTasksClick = { tasksClicked = true },
                    onSettingsClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.home_option_tasks)).performClick()

        assert(tasksClicked) { "Expected onTasksClick to be invoked" }
        assert(!articlesClicked) { "Tapping the Tasks row must not invoke onArticlesClick" }
    }

    @Test
    fun tappingArticlesRow_invokesOnArticlesClick() {
        var tasksClicked = false
        var articlesClicked = false

        composeTestRule.setContent {
            NeverLateTheme {
                HomeScreen(
                    uiState = HomeUiState(name = "Ada"),
                    onArticlesClick = { articlesClicked = true },
                    onTasksClick = { tasksClicked = true },
                    onSettingsClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.home_option_articles)).performClick()

        assert(articlesClicked) { "Expected onArticlesClick to be invoked" }
        assert(!tasksClicked) { "Tapping the Articles row must not invoke onTasksClick" }
    }
}
