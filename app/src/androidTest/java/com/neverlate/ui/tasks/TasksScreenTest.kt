package com.neverlate.ui.tasks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
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

    /**
     * Feature 19: [TaskRow] renders a determinate progress bar (a [androidx.compose.material3.LinearProgressIndicator])
     * only when [com.neverlate.domain.tasks.deadlineProgressFor] returns a non-null fraction, i.e.
     * only when the task has a usable [Task.estimatedDurationMillis]. Asserted via the
     * `ProgressBarRangeInfo` semantics the indicator exposes, rather than reading its animated
     * value directly (the bar eases toward its target via `animateFloatAsState`, so the freshly
     * composed value right after `setContent` need not equal the target yet).
     *
     * Scoped to *this row* (not "any progress bar node in the whole screen") via
     * [hasAnyAncestor]/[hasAnyDescendant] over the **unmerged** semantics tree: `TaskRow`'s `Card`
     * is `.clickable()`, which by default merges its descendants' semantics into one node, and the
     * screen also hosts a `PullToRefreshBox` whose own indicator could in principle expose its own
     * `ProgressBarRangeInfo` regardless of this task's data — an unscoped, merged-tree query could
     * false-positive/false-negative on either. Walking the raw (unmerged) tree for a node that both
     * has the range info *and* shares an ancestor with this task's title text sidesteps both risks.
     */
    private fun progressBarMatcherFor(taskTitle: String): SemanticsMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and
            hasAnyAncestor(hasAnyDescendant(hasText(taskTitle)))

    @Test
    fun content_taskWithUsableEstimatedDuration_rendersProgressBar() {
        val task = Task(id = 1, title = "Preparar la presentación", estimatedDurationMillis = 25 * 60_000L)

        composeTestRule.setContent {
            NeverLateTheme {
                TasksScreen(
                    uiState = TasksUiState.Content(
                        listOf(TaskUiModel(task = task, remainingMillis = 10 * 60_000L, isTimedOut = false)),
                    ),
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

        composeTestRule.onNode(progressBarMatcherFor(task.title), useUnmergedTree = true).assertExists()
    }

    @Test
    fun content_taskWithoutEstimatedDuration_rendersNoProgressBar() {
        // No estimatedDurationMillis -> deadlineProgressFor has no usable total window -> null ->
        // TaskRow must render no bar at all, per that function's KDoc.
        val task = Task(id = 2, title = "Tarea sin duración estimada")

        composeTestRule.setContent {
            NeverLateTheme {
                TasksScreen(
                    uiState = TasksUiState.Content(
                        listOf(TaskUiModel(task = task, remainingMillis = 10 * 60_000L, isTimedOut = false)),
                    ),
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

        composeTestRule.onNode(progressBarMatcherFor(task.title), useUnmergedTree = true).assertDoesNotExist()
    }
}
