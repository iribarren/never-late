package com.neverlate.ui.focus

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.neverlate.R
import com.neverlate.domain.tasks.FocusProgress
import com.neverlate.ui.theme.NeverLateTheme
import org.junit.Rule
import org.junit.Test

/**
 * Modo Foco blindaje (`docs/specs/2026-08-18-focus-mode-shielding.md`, D7/AC-V4/AC-V5/AC-36):
 * drives the **stateless** [FocusScreen] directly with a crafted [FocusUiState.Content] rather
 * than the full [FocusRoute]/[FocusViewModel]/DataStore stack — the indicator's visibility rule
 * is a pure function of `doNotDisturbActive`/`screenPinningActive`, so this is the smallest,
 * fastest-to-reason-about seam that actually exercises it.
 */
class FocusShieldIndicatorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(resId: Int) = targetContext.getString(resId)

    private fun contentState(doNotDisturbActive: Boolean, keepScreenOn: Boolean = true) = FocusUiState.Content(
        rows = emptyList(),
        progress = FocusProgress(total = 1, done = 0),
        exitPanel = ExitPanelUiState(
            isOpen = false,
            enteredCode = "",
            codeFieldVisible = true,
            tasksSatisfied = false,
            codeSatisfied = false,
            slideEnabled = false,
            revealSecondsRemaining = null,
            revealedCode = null,
            showAbandonConfirm = false,
        ),
        doNotDisturbActive = doNotDisturbActive,
        keepScreenOn = keepScreenOn,
    )

    private fun setFocusScreen(uiState: FocusUiState, screenPinningActive: Boolean) {
        composeTestRule.setContent {
            NeverLateTheme {
                FocusScreen(
                    uiState = uiState,
                    screenPinningActive = screenPinningActive,
                    onToggleComplete = {},
                    onExitButtonClick = {},
                    onPanelDismiss = {},
                    onCodeChange = {},
                    onForgetCodeClick = {},
                    onSlideComplete = {},
                    onAbandonClick = {},
                    onAbandonCancel = {},
                    onAbandonConfirm = {},
                    widthSizeClass = WindowWidthSizeClass.Compact,
                )
            }
        }
    }

    @Test
    fun indicator_showsBothMeasures_withTextAndIcon_whenBothAreActive() {
        setFocusScreen(contentState(doNotDisturbActive = true), screenPinningActive = true)

        composeTestRule.onNodeWithText(string(R.string.focus_shield_indicator_do_not_disturb)).assertExists()
        composeTestRule.onNodeWithText(string(R.string.focus_shield_indicator_screen_pinning)).assertExists()
    }

    @Test
    fun indicator_showsOnlyDoNotDisturb_whenOnlyThatMeasureIsActive() {
        setFocusScreen(contentState(doNotDisturbActive = true), screenPinningActive = false)

        composeTestRule.onNodeWithText(string(R.string.focus_shield_indicator_do_not_disturb)).assertExists()
        composeTestRule.onNodeWithText(string(R.string.focus_shield_indicator_screen_pinning)).assertDoesNotExist()
    }

    @Test
    fun indicator_showsOnlyScreenPinning_whenOnlyThatMeasureIsActive() {
        setFocusScreen(contentState(doNotDisturbActive = false), screenPinningActive = true)

        composeTestRule.onNodeWithText(string(R.string.focus_shield_indicator_screen_pinning)).assertExists()
        composeTestRule.onNodeWithText(string(R.string.focus_shield_indicator_do_not_disturb)).assertDoesNotExist()
    }

    @Test
    fun indicator_isAbsentEntirely_whenNoMeasureIsActive_AC_V5() {
        setFocusScreen(contentState(doNotDisturbActive = false), screenPinningActive = false)

        composeTestRule.onNodeWithText(string(R.string.focus_shield_indicator_do_not_disturb)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.focus_shield_indicator_screen_pinning)).assertDoesNotExist()
    }

    @Test
    fun indicator_neverShowsKeepScreenOn_evenWhenActive() {
        // D8/US-6: "Pantalla siempre encendida" is deliberately never part of the indicator — see
        // FocusShieldIndicatorRow's KDoc. keepScreenOn = true here on its own must not surface
        // anything in the indicator strip.
        setFocusScreen(contentState(doNotDisturbActive = false, keepScreenOn = true), screenPinningActive = false)

        composeTestRule.onNodeWithText(string(R.string.focus_shield_keep_screen_on_label)).assertDoesNotExist()
    }
}
