package com.neverlate.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 *
 * Feature 15 wrapped the three existing blocks (Tema, Recordatorios, Cuenta) in Material 3 `Card`s
 * with icon+title headers, reusing the controls verbatim. The tests below guard that the section
 * headers render and that wrapping didn't change any of the existing controls' behaviour (theme
 * selection, the reminders switch, the account action).
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
                    onDynamicColorChanged = {},
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

    @Test
    fun content_showsAllThreeSectionCardHeaders() {
        composeTestRule.setContent {
            NeverLateTheme {
                SettingsScreen(
                    uiState = SettingsUiState(themeMode = ThemeMode.SYSTEM, remindersEnabled = false),
                    onThemeModeSelected = {},
                    onRemindersEnabledChanged = {},
                    onReminderLeadMinutesSelected = {},
                    onDynamicColorChanged = {},
                    onLogoutClick = {},
                    onSignInClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.settings_theme_section))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.settings_reminders_section))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.settings_account_section))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun tappingThemeOption_stillInvokesOnThemeModeSelected_insideCard() {
        var selected: ThemeMode? = null

        composeTestRule.setContent {
            NeverLateTheme {
                SettingsScreen(
                    uiState = SettingsUiState(themeMode = ThemeMode.SYSTEM, remindersEnabled = false),
                    onThemeModeSelected = { selected = it },
                    onRemindersEnabledChanged = {},
                    onReminderLeadMinutesSelected = {},
                    onDynamicColorChanged = {},
                    onLogoutClick = {},
                    onSignInClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.settings_theme_dark))
            .performScrollTo()
            .performClick()

        assert(selected == ThemeMode.DARK) { "Expected onThemeModeSelected(DARK), got $selected" }
    }

    @Test
    fun togglingRemindersSwitch_stillInvokesOnRemindersEnabledChanged_insideCard() {
        var newValue: Boolean? = null

        composeTestRule.setContent {
            NeverLateTheme {
                SettingsScreen(
                    uiState = SettingsUiState(themeMode = ThemeMode.SYSTEM, remindersEnabled = false),
                    onThemeModeSelected = {},
                    onRemindersEnabledChanged = { newValue = it },
                    onReminderLeadMinutesSelected = {},
                    onDynamicColorChanged = {},
                    onLogoutClick = {},
                    onSignInClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNode(isToggleable())
            .performScrollTo()
            .performClick()

        assert(newValue == true) { "Expected onRemindersEnabledChanged(true), got $newValue" }
    }

    @Test
    fun accountSection_guest_showsSignInButton_andInvokesOnSignInClick() {
        var signInClicked = false

        composeTestRule.setContent {
            NeverLateTheme {
                SettingsScreen(
                    uiState = SettingsUiState(
                        themeMode = ThemeMode.SYSTEM,
                        remindersEnabled = false,
                        authState = AuthState.Guest,
                    ),
                    onThemeModeSelected = {},
                    onRemindersEnabledChanged = {},
                    onReminderLeadMinutesSelected = {},
                    onDynamicColorChanged = {},
                    onLogoutClick = {},
                    onSignInClick = { signInClicked = true },
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.settings_sign_in_button))
            .performScrollTo()
            .performClick()

        assert(signInClicked) { "Expected onSignInClick to be invoked for a Guest" }
    }

    @Test
    fun accountSection_loggedIn_tappingLogout_invokesOnLogoutClick() {
        var logoutClicked = false

        composeTestRule.setContent {
            NeverLateTheme {
                SettingsScreen(
                    uiState = SettingsUiState(
                        themeMode = ThemeMode.SYSTEM,
                        remindersEnabled = false,
                        authState = AuthState.LoggedIn(userId = 1L, email = "user@example.com"),
                    ),
                    onThemeModeSelected = {},
                    onRemindersEnabledChanged = {},
                    onReminderLeadMinutesSelected = {},
                    onDynamicColorChanged = {},
                    onLogoutClick = { logoutClicked = true },
                    onSignInClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.settings_logout_button))
            .performScrollTo()
            .performClick()

        assert(logoutClicked) { "Expected onLogoutClick to be invoked when LoggedIn" }
    }
}
