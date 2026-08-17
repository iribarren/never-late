package com.neverlate.ui.focus

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.neverlate.R
import com.neverlate.data.UserPreferences
import com.neverlate.data.tasks.Task
import com.neverlate.domain.tasks.FocusSession
import com.neverlate.ui.theme.NeverLateTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * D9/AC-27 — the one acceptance criterion in `docs/specs/2026-08-18-focus-mode.md` explicitly
 * marked "do not ship without it": proves both honest exits from Modo Foco are completable using
 * **only** semantics/accessibility actions, never the slide bar's drag gesture.
 *
 * [FocusRoute] takes [FocusViewModel] as an ordinary constructor argument here (feature 13d's
 * `@HiltViewModel` is still a plain `@Inject constructor` — see
 * [com.neverlate.ui.tasks.TasksRouteSnackbarTest]'s KDoc for the same technique), so this needs no
 * Hilt test infrastructure at all.
 *
 * "Semantics actions only" is proven three ways here:
 * - The checkbox and every button are driven with [performClick], which Compose UI testing
 *   dispatches through each node's own `SemanticsActions.OnClick` handler, not a raw touch gesture.
 * - The code field is driven with [performTextInput], a real accessible text-input action.
 * - The slide bar's completion is driven by fetching its `SemanticsActions.CustomActions` entry and
 *   invoking its `action` lambda directly — exactly what TalkBack ends up calling when a
 *   screen-reader user selects the action — and explicitly **never** a
 *   [androidx.compose.ui.test.performTouchInput] drag.
 */
class FocusExitAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(resId: Int) = targetContext.getString(resId)

    private val pendingTask = Task(id = 1L, title = "Preparar la presentación")

    private fun activeSessionPreferences(exitCode: String = "1234") = UserPreferences(
        focusSession = FocusSession(
            startedAt = System.currentTimeMillis(),
            exitCode = exitCode,
            roster = setOf(pendingTask.id),
        ),
    )

    @Test
    fun ritualExit_completesUsingOnlySemanticsActions_noSlideDragGesture() {
        val taskRepository = FakeFocusTaskRepository(listOf(pendingTask))
        val userPreferencesRepository = FakeFocusUserPreferencesRepository(activeSessionPreferences())
        val viewModel = FocusViewModel(taskRepository, userPreferencesRepository)

        var completedCount: Int? = null
        composeTestRule.setContent {
            NeverLateTheme {
                FocusRoute(
                    viewModel = viewModel,
                    onSessionCompleted = { count -> completedCount = count },
                    onSessionAbandoned = { throw AssertionError("must not abandon") },
                    widthSizeClass = WindowWidthSizeClass.Compact,
                )
            }
        }

        // 1. Open the exit panel via the persistent bottom button — the same production callback
        // the system back gesture also triggers (D8) — while the roster task is still pending
        // (unambiguous: the "nothing pending left" MessageState, which reuses this same button
        // text as its own action, has not appeared yet).
        composeTestRule.onNodeWithText(string(R.string.focus_exit_button)).performClick()

        // 2. Mark the only roster task done. Compose dispatches performClick() through Checkbox's
        // own SemanticsActions.OnClick handler.
        composeTestRule.onNodeWithContentDescription(string(R.string.tasks_mark_done_content_description))
            .performClick()

        // 3. Type the code — a real accessible text-input action.
        composeTestRule.onNodeWithText(string(R.string.focus_entry_dialog_code_label)).performTextInput("1234")
        composeTestRule.waitForIdle()

        // 4. Complete the slide bar via its CustomAccessibilityAction — never a drag.
        val slideNode = composeTestRule
            .onNodeWithContentDescription(string(R.string.focus_slide_content_description))
            .fetchSemanticsNode()
        val unlockAction = slideNode.config[SemanticsActions.CustomActions]
            .first { it.label == string(R.string.focus_slide_action_label) }
        composeTestRule.runOnUiThread { unlockAction.action.invoke() }
        composeTestRule.waitForIdle()

        assertEquals("the completed count must reflect the one roster task", 1, completedCount)
    }

    @Test
    fun abandon_completesUsingOnlySemanticsActions_neverGatedOnCodeOrTasks() {
        val taskRepository = FakeFocusTaskRepository(listOf(pendingTask))
        val userPreferencesRepository = FakeFocusUserPreferencesRepository(activeSessionPreferences())
        val viewModel = FocusViewModel(taskRepository, userPreferencesRepository)

        var abandoned = false
        composeTestRule.setContent {
            NeverLateTheme {
                FocusRoute(
                    viewModel = viewModel,
                    onSessionCompleted = { throw AssertionError("must not complete") },
                    onSessionAbandoned = { abandoned = true },
                    widthSizeClass = WindowWidthSizeClass.Compact,
                )
            }
        }

        // Deliberately: no task marked done, no code entered — D3's whole point is that abandon
        // works regardless of either gate.
        composeTestRule.onNodeWithText(string(R.string.focus_exit_button)).performClick()
        composeTestRule.onNodeWithText(string(R.string.focus_abandon_button)).performClick()
        composeTestRule.onNodeWithText(string(R.string.focus_abandon_confirm_button)).performClick()
        composeTestRule.waitForIdle()

        assertTrue("abandoning must end the session", abandoned)
        assertTrue("abandoning must never write any task (AC-23)", taskRepository.savedTasks.isEmpty())
    }
}
