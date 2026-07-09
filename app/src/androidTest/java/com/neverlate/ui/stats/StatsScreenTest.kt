package com.neverlate.ui.stats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.neverlate.R
import com.neverlate.domain.tasks.WeeklyTaskStats
import com.neverlate.ui.theme.NeverLateTheme
import java.text.NumberFormat
import org.junit.Rule
import org.junit.Test

/**
 * Drives the stateless [StatsScreen] directly with a hoisted [StatsUiState] (no real
 * [StatsViewModel] or repository involved) — the same pattern
 * [com.neverlate.ui.tasks.TasksScreenTest] already uses for its own stateless screen. Semantics
 * ([onNodeWithText]/[onNodeWithContentDescription]) are the test surface throughout, per the
 * feature's *Testing focus*.
 */
class StatsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(resId: Int): String = targetContext.getString(resId)

    /** The same locale-aware percent formatting [StatsScreen]'s own `formatPercent` uses, so this
     *  assertion matches whatever the device's locale actually renders instead of assuming "80%". */
    private fun percentText(percent: Int): String {
        val locale = targetContext.resources.configuration.locales[0]
        return NumberFormat.getPercentInstance(locale).format(percent / 100.0)
    }

    @Test
    fun content_showsAllThreeStatNumbersAndLabels() {
        val stats = WeeklyTaskStats(completedThisWeek = 5, onTimePercent = 80, dueSoon = 2)

        composeTestRule.setContent {
            NeverLateTheme {
                StatsScreen(uiState = StatsUiState.Content(stats), onBack = {})
            }
        }

        composeTestRule.onNodeWithText("5").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.stats_completed_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(percentText(80)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.stats_on_time_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.stats_due_soon_label)).assertIsDisplayed()
    }

    /** US-2: a `null` ratio (no dated completion this week) renders the neutral "—" placeholder,
     *  never "0%" — a real 0% would misleadingly read as "always late". */
    @Test
    fun content_undefinedOnTimePercent_rendersTheNeutralPlaceholder() {
        val stats = WeeklyTaskStats(completedThisWeek = 1, onTimePercent = null, dueSoon = 0)

        composeTestRule.setContent {
            NeverLateTheme {
                StatsScreen(uiState = StatsUiState.Content(stats), onBack = {})
            }
        }

        composeTestRule.onNodeWithText(string(R.string.stats_on_time_undefined)).assertIsDisplayed()
    }

    /** US-2: no tasks at all uses the shared [com.neverlate.ui.components.MessageState], not a
     *  column of zeros. */
    @Test
    fun empty_showsTheSharedMessageState() {
        composeTestRule.setContent {
            NeverLateTheme {
                StatsScreen(uiState = StatsUiState.Empty, onBack = {})
            }
        }

        composeTestRule.onNodeWithText(string(R.string.stats_empty)).assertIsDisplayed()
    }

    /** US-3: Stats is always reached as a secondary screen, so the back arrow is always shown and
     *  wired to [onBack] — there is no top-level (`onBack = null`) usage to contrast it with, unlike
     *  [com.neverlate.ui.tasks.TasksScreen]. */
    @Test
    fun backArrow_isShownAndInvokesOnBack() {
        var backClicks = 0

        composeTestRule.setContent {
            NeverLateTheme {
                StatsScreen(uiState = StatsUiState.Empty, onBack = { backClicks++ })
            }
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.stats_back_content_description))
            .apply {
                assertIsDisplayed()
                performClick()
            }

        assert(backClicks == 1) { "Expected onBack to be invoked exactly once, was $backClicks" }
    }
}
