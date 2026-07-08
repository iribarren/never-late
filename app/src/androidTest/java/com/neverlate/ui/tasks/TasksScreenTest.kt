package com.neverlate.ui.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.neverlate.R
import com.neverlate.data.tasks.Task
import com.neverlate.ui.theme.NeverLateTheme
import org.junit.Rule
import org.junit.Test

/**
 * Drives the stateless [TasksScreen] directly with hoisted state + callbacks (no real
 * [TasksViewModel] or repository involved), same pattern as
 * [com.neverlate.ui.articles.ArticlesScreenTest]. Kept intentionally small: only the empty state
 * (acceptance criterion 7) and one row-rendering/click case, per the project's preference for a
 * few sturdy UI tests over many fragile ones — the bulk of this feature's logic is already
 * covered by [com.neverlate.ui.tasks.TasksViewModelTest] and the pure-function tests.
 */
class TasksScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun emptyMessage(): String = targetContext.getString(R.string.tasks_empty)

    private fun string(resId: Int): String = targetContext.getString(resId)

    @Test
    fun emptyState_showsEmptyMessage() {
        composeTestRule.setContent {
            NeverLateTheme {
                TasksScreen(
                    uiState = TasksUiState.Empty,
                    syncStatus = com.neverlate.data.sync.SyncStatus.Idle,
                    onRefresh = {},
                    onAddTaskClick = {},
                    onTaskClick = {},
                    onStartClick = {},
                    onPauseClick = {},
                    onDeleteClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(emptyMessage()).assertExists()
    }

    @Test
    fun content_rendersTaskTitle_andTappingRowInvokesOnTaskClickWithItsId() {
        val task = Task(id = 42, title = "Preparar la presentación", estimatedDurationMillis = 25 * 60_000L)
        var clickedId: Long? = null

        composeTestRule.setContent {
            NeverLateTheme {
                TasksScreen(
                    uiState = TasksUiState.Content(
                        listOf(TaskUiModel(task = task, remainingMillis = 25 * 60_000L, isTimedOut = false)),
                    ),
                    syncStatus = com.neverlate.data.sync.SyncStatus.Idle,
                    onRefresh = {},
                    onAddTaskClick = {},
                    onTaskClick = { clickedId = it },
                    onStartClick = {},
                    onPauseClick = {},
                    onDeleteClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(task.title).apply {
            assertExists()
            performClick()
        }

        assert(clickedId == task.id) { "Expected onTaskClick to be invoked with ${task.id}, got $clickedId" }
    }

    /**
     * Feature 18: as a top-level bottom-bar tab, Tasks is reached laterally, so it shows no back
     * arrow ([onBack] is `null`); a non-null [onBack] (secondary/pushed usage) restores it.
     */
    @Test
    fun topLevelUsage_onBackNull_hidesBackArrow() {
        composeTestRule.setContent {
            NeverLateTheme {
                TasksScreen(
                    uiState = TasksUiState.Empty,
                    syncStatus = com.neverlate.data.sync.SyncStatus.Idle,
                    onRefresh = {},
                    onAddTaskClick = {},
                    onTaskClick = {},
                    onStartClick = {},
                    onPauseClick = {},
                    onDeleteClick = {},
                    onBack = null,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.tasks_back_content_description))
            .assertDoesNotExist()
    }

    @Test
    fun secondaryUsage_onBackProvided_showsBackArrow() {
        composeTestRule.setContent {
            NeverLateTheme {
                TasksScreen(
                    uiState = TasksUiState.Empty,
                    syncStatus = com.neverlate.data.sync.SyncStatus.Idle,
                    onRefresh = {},
                    onAddTaskClick = {},
                    onTaskClick = {},
                    onStartClick = {},
                    onPauseClick = {},
                    onDeleteClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.tasks_back_content_description))
            .assertIsDisplayed()
    }
}
