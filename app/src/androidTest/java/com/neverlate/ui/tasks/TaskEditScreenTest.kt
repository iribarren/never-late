package com.neverlate.ui.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.neverlate.R
import com.neverlate.data.tasks.TaskFormResult
import com.neverlate.data.tasks.durationParts
import com.neverlate.data.tasks.validateTaskForm
import com.neverlate.ui.theme.NeverLateTheme
import org.junit.Rule
import org.junit.Test

/**
 * Drives the stateless [TaskEditScreen] directly with hoisted state + callbacks (no real
 * [TaskEditViewModel]/repository involved), same pattern as [TasksScreenTest]. Feature
 * "duration-hours-minutes" (`docs/specs/2026-08-16-duration-hours-minutes.md`) replaced the single
 * duration field with an hours+minutes pair — these tests pin US-1 (typing into either field
 * reports its own text through its own callback) and the US-2 pre-fill/round-trip: driving the
 * *real* [validateTaskForm] and [durationParts] (not fakes) so the split-then-recombine path is
 * exercised the same way [TaskEditViewModel] exercises it, just at the UI layer.
 */
class TaskEditScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(resId: Int): String = targetContext.getString(resId)

    @Test
    fun typingIntoHoursField_invokesOnDurationHoursChangeWithTypedText() {
        var lastHours: String? = null

        composeTestRule.setContent {
            NeverLateTheme {
                TaskEditScreen(
                    uiState = TaskEditUiState(title = "Preparar informe"),
                    isEditing = false,
                    onTitleChange = {},
                    onDurationHoursChange = { lastHours = it },
                    onDurationMinutesChange = {},
                    onDeadlineChange = {},
                    onPriorityChange = {},
                    onSaveClick = {},
                    onDeleteClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.task_edit_duration_hours_label)).performTextInput("2")

        assert(lastHours == "2") { "Expected onDurationHoursChange to be invoked with \"2\", got $lastHours" }
    }

    @Test
    fun typingIntoMinutesField_invokesOnDurationMinutesChangeWithTypedText() {
        var lastMinutes: String? = null

        composeTestRule.setContent {
            NeverLateTheme {
                TaskEditScreen(
                    uiState = TaskEditUiState(title = "Preparar informe"),
                    isEditing = false,
                    onTitleChange = {},
                    onDurationHoursChange = {},
                    onDurationMinutesChange = { lastMinutes = it },
                    onDeadlineChange = {},
                    onPriorityChange = {},
                    onSaveClick = {},
                    onDeleteClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.task_edit_duration_minutes_label)).performTextInput("30")

        assert(lastMinutes == "30") { "Expected onDurationMinutesChange to be invoked with \"30\", got $lastMinutes" }
    }

    /**
     * US-2's full round trip, driven at the UI layer: type `2` h / `30` min, tap save (which runs
     * the real [validateTaskForm] against the hoisted state, exactly like [TaskEditViewModel.save]
     * does), then "reopen" by re-splitting the resulting millis through the real [durationParts] —
     * the same helper [TaskEditViewModel]'s `init{}` uses — and assert both fields redisplay the
     * value that was originally typed. Pins that entry and pre-fill agree, guarding the spec's
     * "Round-trip drift" risk from the UI side (see [TaskEditViewModelTest] for the ViewModel-level
     * version of the same guard).
     */
    @Test
    fun enteringHoursAndMinutes_savingAndReopening_prefillsBothFieldsFromTheStoredMillis() {
        var uiState by mutableStateOf(TaskEditUiState(title = "Preparar informe"))
        var savedMillis: Long? = null

        composeTestRule.setContent {
            NeverLateTheme {
                TaskEditScreen(
                    uiState = uiState,
                    isEditing = false,
                    onTitleChange = { uiState = uiState.copy(title = it) },
                    onDurationHoursChange = { uiState = uiState.copy(durationHours = it, validationError = null) },
                    onDurationMinutesChange = { uiState = uiState.copy(durationMinutes = it, validationError = null) },
                    onDeadlineChange = { uiState = uiState.copy(deadlineText = it) },
                    onPriorityChange = {},
                    onSaveClick = {
                        when (
                            val result = validateTaskForm(
                                title = uiState.title,
                                durationHoursText = uiState.durationHours,
                                durationMinutesText = uiState.durationMinutes,
                                deadlineText = uiState.deadlineText,
                            )
                        ) {
                            is TaskFormResult.Valid -> savedMillis = result.durationMillis
                            is TaskFormResult.Invalid -> uiState = uiState.copy(validationError = result.error)
                        }
                    },
                    onDeleteClick = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.task_edit_duration_hours_label)).performTextInput("2")
        composeTestRule.onNodeWithText(string(R.string.task_edit_duration_minutes_label)).performTextInput("30")
        composeTestRule.onNodeWithText(string(R.string.task_edit_save_button)).performClick()

        assert(savedMillis == 150 * 60_000L) { "Expected save to produce 150 minutes of millis, got $savedMillis" }

        // "Reopen": mirror TaskEditViewModel's init{} pre-fill exactly (durationParts, a zero part
        // rendered as an empty string) and let the same composition recompose with it.
        val (hours, minutes) = durationParts(savedMillis!!)
        uiState = uiState.copy(
            durationHours = hours.takeIf { it != 0L }?.toString().orEmpty(),
            durationMinutes = minutes.takeIf { it != 0L }?.toString().orEmpty(),
        )

        composeTestRule.onNodeWithText("2").assertExists()
        composeTestRule.onNodeWithText("30").assertExists()
    }
}
