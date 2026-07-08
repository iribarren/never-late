package com.neverlate.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.neverlate.ui.theme.NeverLateTheme
import org.junit.Rule
import org.junit.Test

/**
 * Drives [MessageState] directly with hoisted parameters (no screen/ViewModel involved), same
 * pattern as [com.neverlate.ui.tasks.TasksScreenTest] and [com.neverlate.ui.articles.ArticlesScreenTest].
 * Covers the three things every caller relies on: the message renders, the optional action button
 * shows and fires [onAction] when both [actionLabel]/onAction] are supplied, and it is absent when
 * they are not (per the composable's own KDoc: the button only renders when *both* are non-null).
 */
class MessageStateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val message = "No hay tareas todavía."
    private val actionLabel = "Crear tarea"

    @Test
    fun rendersTheMessageText() {
        composeTestRule.setContent {
            NeverLateTheme {
                MessageState(icon = Icons.Filled.ErrorOutline, message = message)
            }
        }

        composeTestRule.onNodeWithText(message).assertExists()
    }

    @Test
    fun withActionLabelAndOnAction_showsButton_andInvokesOnActionWhenTapped() {
        var actionCount = 0

        composeTestRule.setContent {
            NeverLateTheme {
                MessageState(
                    icon = Icons.Filled.ErrorOutline,
                    message = message,
                    actionLabel = actionLabel,
                    onAction = { actionCount++ },
                )
            }
        }

        composeTestRule.onNodeWithText(actionLabel).apply {
            assertExists()
            performClick()
        }

        assert(actionCount == 1) { "Expected onAction to be invoked exactly once, was $actionCount" }
    }

    @Test
    fun withNoAction_doesNotShowAnyButton() {
        composeTestRule.setContent {
            NeverLateTheme {
                MessageState(icon = Icons.Filled.ErrorOutline, message = message)
            }
        }

        composeTestRule.onNodeWithText(message).assertExists()
        // No actionLabel/onAction supplied: MessageState must render no button at all, so there is
        // no node bearing the label this test would otherwise have used for the action.
        composeTestRule.onNodeWithText(actionLabel).assertDoesNotExist()
    }
}
