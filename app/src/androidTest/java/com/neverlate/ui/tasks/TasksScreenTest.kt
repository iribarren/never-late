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

    /**
     * Feature 20: [TaskRow] now wraps its title/countdown/buttons [Column] together with a leading
     * [com.neverlate.ui.components.BrandIconChip] inside a [androidx.compose.foundation.layout.Row],
     * and the `Column` gained `Modifier.weight(1f)`. Guards against that restructuring having
     * disturbed the existing start/pause/delete `IconButton`s or their click callbacks: a not-yet-
     * started task shows "start" (not "pause"), and tapping it still invokes [onStartClick] with the
     * task's id.
     */
    @Test
    fun content_notRunningTask_startButtonStillInvokesOnStartClickWithItsId() {
        val task = Task(id = 7, title = "Repasar apuntes")
        var startedId: Long? = null

        composeTestRule.setContent {
            NeverLateTheme {
                TasksScreen(
                    uiState = TasksUiState.Content(
                        listOf(TaskUiModel(task = task, remainingMillis = 0L, isTimedOut = false)),
                    ),
                    syncStatus = com.neverlate.data.sync.SyncStatus.Idle,
                    onRefresh = {},
                    onAddTaskClick = {},
                    onTaskClick = {},
                    onStartClick = { startedId = it },
                    onPauseClick = {},
                    onDeleteClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.tasks_start_content_description))
            .performClick()

        assert(startedId == task.id) { "Expected onStartClick to be invoked with ${task.id}, got $startedId" }
    }

    /**
     * Same guard as above, for a **running** task: the button shows "pause" instead of "start", and
     * tapping it still invokes [onPauseClick] with the task's id, unaffected by the chip/Row wrapper.
     */
    @Test
    fun content_runningTask_pauseButtonStillInvokesOnPauseClickWithItsId() {
        val task = Task(id = 8, title = "Repasar apuntes", timerEndsAt = Long.MAX_VALUE)
        var pausedId: Long? = null

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
                    onPauseClick = { pausedId = it },
                    onDeleteClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.tasks_pause_content_description))
            .performClick()

        assert(pausedId == task.id) { "Expected onPauseClick to be invoked with ${task.id}, got $pausedId" }
    }

    /**
     * Same guard, for delete: tapping the delete [IconButton][androidx.compose.material3.IconButton]
     * still opens the confirmation dialog, and confirming it still invokes [onDeleteClick] with the
     * task's id — the dialog and its wiring live outside the Row the chip was added to, but this
     * pins the whole interaction end-to-end.
     */
    @Test
    fun content_deleteButton_confirmDialog_stillInvokesOnDeleteClickWithItsId() {
        val task = Task(id = 9, title = "Repasar apuntes")
        var deletedId: Long? = null

        composeTestRule.setContent {
            NeverLateTheme {
                TasksScreen(
                    uiState = TasksUiState.Content(
                        listOf(TaskUiModel(task = task, remainingMillis = 0L, isTimedOut = false)),
                    ),
                    syncStatus = com.neverlate.data.sync.SyncStatus.Idle,
                    onRefresh = {},
                    onAddTaskClick = {},
                    onTaskClick = {},
                    onStartClick = {},
                    onPauseClick = {},
                    onDeleteClick = { deletedId = it },
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.tasks_delete_content_description))
            .performClick()
        composeTestRule.onNodeWithText(string(R.string.tasks_delete_confirm_button)).performClick()

        assert(deletedId == task.id) { "Expected onDeleteClick to be invoked with ${task.id}, got $deletedId" }
    }

    /**
     * Feature 20: the Tasks FAB now sets explicit branded `containerColor`/`contentColor`, kept on
     * the *existing* [androidx.compose.material3.FloatingActionButton] (not a new composable) — this
     * pins that the FAB is still found by its existing `contentDescription` and still fires
     * [onAddTaskClick], i.e. the color-only change didn't disturb the button itself.
     */
    @Test
    fun addTaskFab_isPresentAndStillInvokesOnAddTaskClick() {
        var addTaskClicks = 0

        composeTestRule.setContent {
            NeverLateTheme {
                TasksScreen(
                    uiState = TasksUiState.Empty,
                    syncStatus = com.neverlate.data.sync.SyncStatus.Idle,
                    onRefresh = {},
                    onAddTaskClick = { addTaskClicks++ },
                    onTaskClick = {},
                    onStartClick = {},
                    onPauseClick = {},
                    onDeleteClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.tasks_add_content_description))
            .apply {
                assertIsDisplayed()
                performClick()
            }

        assert(addTaskClicks == 1) { "Expected onAddTaskClick to be invoked exactly once, was $addTaskClicks" }
    }
}
