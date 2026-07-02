package com.neverlate.ui.onboarding

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.neverlate.R
import com.neverlate.ui.theme.NeverLateTheme
import org.junit.Rule
import org.junit.Test

/**
 * Drives the stateless [OnboardingScreen] directly with hoisted state + callbacks (no real
 * [OnboardingViewModel] or DataStore involved), following the same state-hoisting pattern the
 * production composable itself uses.
 */
class OnboardingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // createComposeRule() (rather than createAndroidComposeRule<...>()) doesn't expose an
    // `.activity`, so string resources are read from the instrumentation's target context
    // instead — this also keeps the test decoupled from any specific hosting Activity.
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun saveButtonLabel(): String = targetContext.getString(R.string.onboarding_save_button)

    private fun nameLabel(): String = targetContext.getString(R.string.onboarding_name_label)

    @Test
    fun saveButton_isDisabled_whenNameIsBlank() {
        composeTestRule.setContent {
            NeverLateTheme {
                OnboardingScreen(
                    uiState = OnboardingUiState(name = "", isSaveEnabled = false),
                    onNameChange = {},
                    onSave = {},
                )
            }
        }

        composeTestRule.onNodeWithText(saveButtonLabel()).assertIsNotEnabled()
    }

    @Test
    fun saveButton_isEnabled_whenNameIsNotBlank() {
        composeTestRule.setContent {
            NeverLateTheme {
                OnboardingScreen(
                    uiState = OnboardingUiState(name = "Ada", isSaveEnabled = true),
                    onNameChange = {},
                    onSave = {},
                )
            }
        }

        composeTestRule.onNodeWithText(saveButtonLabel()).assertIsEnabled()
    }

    @Test
    fun typingInNameField_invokesOnNameChange() {
        var receivedName: String? = null

        composeTestRule.setContent {
            NeverLateTheme {
                OnboardingScreen(
                    uiState = OnboardingUiState(name = "", isSaveEnabled = false),
                    onNameChange = { receivedName = it },
                    onSave = {},
                )
            }
        }

        composeTestRule.onNodeWithText(nameLabel()).performTextInput("Ada")

        assert(receivedName == "Ada") { "Expected onNameChange to be called with \"Ada\", got $receivedName" }
    }

    @Test
    fun tappingEnabledSaveButton_invokesOnSave() {
        var saveCount = 0

        composeTestRule.setContent {
            NeverLateTheme {
                OnboardingScreen(
                    uiState = OnboardingUiState(name = "Ada", isSaveEnabled = true),
                    onNameChange = {},
                    onSave = { saveCount++ },
                )
            }
        }

        composeTestRule.onNodeWithText(saveButtonLabel()).performClick()

        assert(saveCount == 1) { "Expected onSave to be invoked exactly once, was $saveCount" }
    }
}
