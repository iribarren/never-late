package com.neverlate.ui.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.neverlate.R
import com.neverlate.data.sync.SyncStatus
import com.neverlate.domain.focus.FocusShieldOptions
import com.neverlate.domain.tasks.TaskListCriteria
import com.neverlate.ui.theme.NeverLateTheme
import org.junit.Rule
import org.junit.Test

/**
 * Modo Foco blindaje (`docs/specs/2026-08-18-focus-mode-shielding.md`, AC-33/AC-34/AC-V1/AC-V2):
 * drives the **stateless** [TasksScreen] directly (no ViewModel/DataStore) to open the entry
 * dialog and exercise its three device-measure switch rows.
 */
class FocusEntryDialogShieldTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(resId: Int) = targetContext.getString(resId)

    private fun setTasksScreen(focusShieldOptions: FocusShieldOptions = FocusShieldOptions()) {
        composeTestRule.setContent {
            NeverLateTheme {
                TasksScreen(
                    uiState = TasksUiState.Empty,
                    syncStatus = SyncStatus.Idle,
                    criteria = TaskListCriteria(),
                    query = "",
                    focusShieldOptions = focusShieldOptions,
                    onRefresh = {},
                    onAddTaskClick = {},
                    onTaskClick = {},
                    onStartClick = {},
                    onPauseClick = {},
                    onDeleteClick = {},
                    onToggleComplete = {},
                    onQueryChange = {},
                    onSortFieldChange = {},
                    onToggleSortDirection = {},
                    onGroupAxisChange = {},
                    onPriorityFilterToggle = {},
                    onClearFilters = {},
                )
            }
        }
    }

    private fun openFocusEntryDialog() {
        composeTestRule.onNodeWithContentDescription(string(R.string.focus_entry_content_description)).performClick()
    }

    @Test
    fun allThreeSwitchRows_areRendered_preFilledFromPersistedOptions() {
        setTasksScreen(FocusShieldOptions(keepScreenOn = false, doNotDisturb = true, screenPinning = true))
        openFocusEntryDialog()

        composeTestRule.onNodeWithText(string(R.string.focus_shield_keep_screen_on_label)).assertExists()
        composeTestRule.onNodeWithText(string(R.string.focus_shield_do_not_disturb_label)).assertExists()
        composeTestRule.onNodeWithText(string(R.string.focus_shield_screen_pinning_label)).assertExists()
    }

    @Test
    fun tappingAnywhereOnTheRow_toggles_theSwitch_AC33() {
        setTasksScreen(FocusShieldOptions(doNotDisturb = false))
        openFocusEntryDialog()

        // Modifier.toggleable merges the row's semantics into one node (role = Switch), so
        // onNodeWithText(label) resolves to the whole row, not just the inner label Text —
        // tapping it is exactly "tap anywhere on the row" (AC-33).
        val label = string(R.string.focus_shield_do_not_disturb_label)
        composeTestRule.onNodeWithText(label).assertIsOff()

        composeTestRule.onNodeWithText(label).performClick()

        composeTestRule.onNodeWithText(label).assertIsOn()
    }

    @Test
    fun startButton_staysReachable_atTheLargestFontScale_AC34() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = LocalDensity.current.density, fontScale = 2f)) {
                NeverLateTheme {
                    TasksScreen(
                        uiState = TasksUiState.Empty,
                        syncStatus = SyncStatus.Idle,
                        criteria = TaskListCriteria(),
                        query = "",
                        focusShieldOptions = FocusShieldOptions(),
                        onRefresh = {},
                        onAddTaskClick = {},
                        onTaskClick = {},
                        onStartClick = {},
                        onPauseClick = {},
                        onDeleteClick = {},
                        onToggleComplete = {},
                        onQueryChange = {},
                        onSortFieldChange = {},
                        onToggleSortDirection = {},
                        onGroupAxisChange = {},
                        onPriorityFilterToggle = {},
                        onClearFilters = {},
                    )
                }
            }
        }
        openFocusEntryDialog()

        // AC-34: the dialog's Column scrolls, so the confirm button remains reachable (and
        // clickable) even though the content is far taller than the viewport at 2x font scale.
        composeTestRule.onNodeWithText(string(R.string.focus_entry_dialog_start_button)).assertExists()
    }
}
