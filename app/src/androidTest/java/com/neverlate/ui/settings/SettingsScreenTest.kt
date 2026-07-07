package com.neverlate.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.neverlate.R
import com.neverlate.data.ThemeMode
import com.neverlate.data.auth.AuthState
import com.neverlate.ui.theme.NeverLateTheme
import org.junit.Rule
import org.junit.Test

/**
 * Drives the stateless [SettingsScreen] directly with hoisted state + callbacks, same pattern as
 * [com.neverlate.ui.tasks.TasksScreenTest]. Guards the scroll regression: with reminders on, the
 * lead-time radio list + exact-alarm notice can push the Account section below the viewport, so the
 * whole screen must be scrollable for it to stay reachable.
 */
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(resId: Int): String = targetContext.getString(resId)

    @Test
    fun remindersEnabled_accountSectionIsReachableByScrolling() {
        composeTestRule.setContent {
            NeverLateTheme {
                SettingsScreen(
                    uiState = SettingsUiState(
                        themeMode = ThemeMode.SYSTEM,
                        remindersEnabled = true,
                        reminderLeadMinutes = 60,
                        authState = AuthState.LoggedIn(userId = 1L, email = "user@example.com"),
                    ),
                    onThemeModeSelected = {},
                    onRemindersEnabledChanged = {},
                    onReminderLeadMinutesSelected = {},
                    onLogoutClick = {},
                    onSignInClick = {},
                    onBack = {},
                )
            }
        }

        // The Account section exists in the tree but may start off-screen; scrolling to it must
        // succeed (it fails if the container is not scrollable) and then it must be displayed.
        composeTestRule.onNodeWithText(string(R.string.settings_account_section))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.settings_logout_button))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
