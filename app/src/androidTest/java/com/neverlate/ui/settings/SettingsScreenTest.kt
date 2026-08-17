package com.neverlate.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
                    onNameChanged = {},
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
                    onNameChanged = {},
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
                    onNameChanged = {},
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
                    onNameChanged = {},
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
                    onNameChanged = {},
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

    /**
     * Feature 18 (US-4): logout is destructive (feature 13 wipes local tasks on sign-out), so it is
     * now guarded by a confirmation [androidx.compose.material3.AlertDialog] — a single tap on "Log
     * out" must NOT log out immediately; it only opens the dialog.
     *
     * Note: `settings_logout_button`, `settings_logout_confirm_title` and
     * `settings_logout_confirm_button` all render the same text ("Log out" / "Cerrar sesión"), so the
     * confirm button is selected by scoping to the dialog window ([isDialog]) plus [hasClickAction]
     * (the title has no click action, the trigger button is outside the dialog) — text alone is
     * ambiguous here.
     */
    private fun loggedInUiState() = SettingsUiState(
        themeMode = ThemeMode.SYSTEM,
        remindersEnabled = false,
        authState = AuthState.LoggedIn(userId = 1L, email = "user@example.com"),
    )

    @Test
    fun accountSection_loggedIn_tappingLogout_opensConfirmDialog_withoutLoggingOut() {
        var logoutClicked = false

        composeTestRule.setContent {
            NeverLateTheme {
                SettingsScreen(
                    uiState = loggedInUiState(),
                    onThemeModeSelected = {},
                    onRemindersEnabledChanged = {},
                    onReminderLeadMinutesSelected = {},
                    onDynamicColorChanged = {},
                    onNameChanged = {},
                    onLogoutClick = { logoutClicked = true },
                    onSignInClick = {},
                    onBack = {},
                )
            }
        }

        // No dialog until the button is tapped.
        composeTestRule.onNodeWithText(string(R.string.settings_logout_confirm_message)).assertDoesNotExist()

        composeTestRule.onNodeWithText(string(R.string.settings_logout_button))
            .performScrollTo()
            .performClick()

        // The dialog (its unique message) is now shown, and logout has NOT fired yet.
        composeTestRule.onNodeWithText(string(R.string.settings_logout_confirm_message)).assertIsDisplayed()
        assert(!logoutClicked) { "Tapping 'Log out' must open the dialog, not log out immediately" }
    }

    @Test
    fun logoutDialog_confirm_invokesOnLogoutClick() {
        var logoutClicked = false

        composeTestRule.setContent {
            NeverLateTheme {
                SettingsScreen(
                    uiState = loggedInUiState(),
                    onThemeModeSelected = {},
                    onRemindersEnabledChanged = {},
                    onReminderLeadMinutesSelected = {},
                    onDynamicColorChanged = {},
                    onNameChanged = {},
                    onLogoutClick = { logoutClicked = true },
                    onSignInClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.settings_logout_button))
            .performScrollTo()
            .performClick()

        // Scope to the dialog: the confirm button shares its text with the trigger and the title.
        composeTestRule.onNode(
            hasText(string(R.string.settings_logout_confirm_button))
                and hasClickAction()
                and hasAnyAncestor(isDialog()),
        ).performClick()

        assert(logoutClicked) { "Confirming the dialog must invoke onLogoutClick" }
    }

    @Test
    fun logoutDialog_cancel_dismissesWithoutLoggingOut() {
        var logoutClicked = false

        composeTestRule.setContent {
            NeverLateTheme {
                SettingsScreen(
                    uiState = loggedInUiState(),
                    onThemeModeSelected = {},
                    onRemindersEnabledChanged = {},
                    onReminderLeadMinutesSelected = {},
                    onDynamicColorChanged = {},
                    onNameChanged = {},
                    onLogoutClick = { logoutClicked = true },
                    onSignInClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.settings_logout_button))
            .performScrollTo()
            .performClick()

        composeTestRule.onNodeWithText(string(R.string.settings_logout_cancel_button)).performClick()

        // Dialog gone, and logout never fired.
        composeTestRule.onNodeWithText(string(R.string.settings_logout_confirm_message)).assertDoesNotExist()
        assert(!logoutClicked) { "Cancelling the dialog must not invoke onLogoutClick" }
    }

    /**
     * Feature 18: as a top-level bottom-bar tab, Settings is reached laterally (not pushed), so it
     * shows no back arrow — [onBack] is `null`. When it is still used as a pushed secondary screen
     * (e.g. a future flow), a non-null [onBack] restores the arrow. Tasks/Articles share this exact
     * convention (see their screens' KDoc).
     */
    @Test
    fun topLevelUsage_onBackNull_hidesBackArrow() {
        composeTestRule.setContent {
            NeverLateTheme {
                SettingsScreen(
                    uiState = SettingsUiState(themeMode = ThemeMode.SYSTEM, remindersEnabled = false),
                    onThemeModeSelected = {},
                    onRemindersEnabledChanged = {},
                    onReminderLeadMinutesSelected = {},
                    onDynamicColorChanged = {},
                    onNameChanged = {},
                    onLogoutClick = {},
                    onSignInClick = {},
                    onBack = null,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.settings_back_content_description))
            .assertDoesNotExist()
    }

    @Test
    fun secondaryUsage_onBackProvided_showsBackArrow() {
        composeTestRule.setContent {
            NeverLateTheme {
                SettingsScreen(
                    uiState = SettingsUiState(themeMode = ThemeMode.SYSTEM, remindersEnabled = false),
                    onThemeModeSelected = {},
                    onRemindersEnabledChanged = {},
                    onReminderLeadMinutesSelected = {},
                    onDynamicColorChanged = {},
                    onNameChanged = {},
                    onLogoutClick = {},
                    onSignInClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.settings_back_content_description))
            .assertIsDisplayed()
    }

    // Editable profile name (editable-profile-name spec, D2/D3/US-2) -----------------------------

    /**
     * The rename dialog's [androidx.compose.material3.OutlinedTextField], scoped by
     * [hasSetTextAction] rather than by its label text alone — `settings_name_field_label` and
     * `settings_name_dialog_title` deliberately render the same string ("Tu nombre"/"Your name"),
     * so a plain [onNodeWithText] on the label would match both the dialog title and the field.
     */
    private fun nameDialogField() =
        composeTestRule.onNode(hasText(string(R.string.settings_name_field_label)) and hasSetTextAction())

    private fun nameUiState(name: String) = SettingsUiState(
        themeMode = ThemeMode.SYSTEM,
        remindersEnabled = false,
        authState = AuthState.LoggedIn(userId = 1L, email = "user@example.com"),
        name = name,
    )

    @Test
    fun nameRow_tappingCambiar_opensDialogPrefilledWithCurrentName() {
        composeTestRule.setContent {
            NeverLateTheme {
                SettingsScreen(
                    uiState = nameUiState("Ada"),
                    onThemeModeSelected = {},
                    onRemindersEnabledChanged = {},
                    onReminderLeadMinutesSelected = {},
                    onDynamicColorChanged = {},
                    onNameChanged = {},
                    onLogoutClick = {},
                    onSignInClick = {},
                    onBack = {},
                )
            }
        }

        // No dialog until Cambiar is tapped.
        composeTestRule.onNodeWithText(string(R.string.settings_name_dialog_title)).assertDoesNotExist()

        composeTestRule.onNodeWithText(string(R.string.settings_name_edit_button))
            .performScrollTo()
            .performClick()

        composeTestRule.onNodeWithText(string(R.string.settings_name_dialog_title)).assertIsDisplayed()
        // Pre-filled: the OutlinedTextField's value is the current name. Scoped to the dialog's
        // editable field (hasSetTextAction) because the Settings row behind the dialog also shows
        // "Ada" as its own value text — matching on text alone would find both.
        composeTestRule.onNode(hasText("Ada") and hasSetTextAction() and hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
    }

    @Test
    fun nameDialog_blankField_disablesGuardar() {
        composeTestRule.setContent {
            NeverLateTheme {
                SettingsScreen(
                    uiState = nameUiState("Ada"),
                    onThemeModeSelected = {},
                    onRemindersEnabledChanged = {},
                    onReminderLeadMinutesSelected = {},
                    onDynamicColorChanged = {},
                    onNameChanged = {},
                    onLogoutClick = {},
                    onSignInClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.settings_name_edit_button))
            .performScrollTo()
            .performClick()

        nameDialogField().performTextClearance()

        composeTestRule.onNode(
            hasText(string(R.string.settings_name_save_button))
                and hasClickAction()
                and hasAnyAncestor(isDialog()),
        ).assertIsNotEnabled()

        // D3/V8: the accessible error is shown as supporting text, not just a disabled button.
        composeTestRule.onNodeWithText(string(R.string.settings_name_error_empty)).assertIsDisplayed()
    }

    @Test
    fun nameDialog_cancelar_dismissesWithoutInvokingOnNameChanged() {
        var newName: String? = null

        composeTestRule.setContent {
            NeverLateTheme {
                SettingsScreen(
                    uiState = nameUiState("Ada"),
                    onThemeModeSelected = {},
                    onRemindersEnabledChanged = {},
                    onReminderLeadMinutesSelected = {},
                    onDynamicColorChanged = {},
                    onNameChanged = { newName = it },
                    onLogoutClick = {},
                    onSignInClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.settings_name_edit_button))
            .performScrollTo()
            .performClick()

        nameDialogField().performTextClearance()
        nameDialogField().performTextInput("Bea")

        composeTestRule.onNode(
            hasText(string(R.string.settings_name_cancel_button))
                and hasClickAction()
                and hasAnyAncestor(isDialog()),
        ).performClick()

        composeTestRule.onNodeWithText(string(R.string.settings_name_dialog_title)).assertDoesNotExist()
        assert(newName == null) { "Cancelar must not invoke onNameChanged, got $newName" }
    }

    @Test
    fun nameDialog_guardar_invokesOnNameChangedWithTrimmedValue() {
        var newName: String? = null

        composeTestRule.setContent {
            NeverLateTheme {
                SettingsScreen(
                    uiState = nameUiState("Ada"),
                    onThemeModeSelected = {},
                    onRemindersEnabledChanged = {},
                    onReminderLeadMinutesSelected = {},
                    onDynamicColorChanged = {},
                    onNameChanged = { newName = it },
                    onLogoutClick = {},
                    onSignInClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.settings_name_edit_button))
            .performScrollTo()
            .performClick()

        nameDialogField().performTextClearance()
        nameDialogField().performTextInput("  Bea  ")

        composeTestRule.onNode(
            hasText(string(R.string.settings_name_save_button))
                and hasClickAction()
                and hasAnyAncestor(isDialog()),
        ).performClick()

        composeTestRule.onNodeWithText(string(R.string.settings_name_dialog_title)).assertDoesNotExist()
        assert(newName == "Bea") { "Guardar must invoke onNameChanged with the trimmed value, got $newName" }
    }
}
