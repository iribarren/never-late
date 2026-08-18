package com.neverlate.ui.focus

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.neverlate.R
import com.neverlate.data.FakeUserPreferencesRepository
import com.neverlate.data.UserPreferences
import com.neverlate.data.tasks.Task
import com.neverlate.domain.focus.FocusShieldOptions
import com.neverlate.domain.tasks.FocusSession
import com.neverlate.ui.theme.NeverLateTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Modo Foco blindaje (`docs/specs/2026-08-18-focus-mode-shielding.md`, D8/AC-26/AC-27/AC-28/AC-V6):
 * needs a real hosting `Activity` (unlike [FocusShieldIndicatorTest]) since the effect under test
 * lives on the Activity's own `Window` — `createAndroidComposeRule<ComponentActivity>()` is the one
 * exception in this test suite that reaches for it, precisely because there is no other way to
 * read `window.attributes.flags`.
 */
class FocusShieldWindowEffectsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(resId: Int) = targetContext.getString(resId)

    private val pendingTask = Task(id = 1L, title = "Preparar la presentación")

    private fun hasKeepScreenOnFlag(): Boolean {
        val flags = composeTestRule.activity.window.attributes.flags
        return (flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
    }

    @Test
    fun flagKeepScreenOn_isAbsentBeforeAndAfterFocusScreen_presentWhileItIsShown_AC26_AC28() {
        val taskRepository = FakeFocusTaskRepository(listOf(pendingTask))
        val userPreferencesRepository = FakeUserPreferencesRepository(
            UserPreferences(
                focusSession = FocusSession(
                    startedAt = System.currentTimeMillis(),
                    exitCode = "1234",
                    roster = setOf(pendingTask.id),
                ),
                // D11 default is already keepScreenOn = true, spelled out here for clarity.
                focusShieldOptions = FocusShieldOptions(keepScreenOn = true),
            ),
        )
        val viewModel = FocusViewModel(taskRepository, userPreferencesRepository, FakeFocusShieldController())

        var showFocusScreen by mutableStateOf(false)
        composeTestRule.setContent {
            NeverLateTheme {
                if (showFocusScreen) {
                    FocusRoute(
                        viewModel = viewModel,
                        onSessionCompleted = {},
                        onSessionAbandoned = {},
                        widthSizeClass = WindowWidthSizeClass.Compact,
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }
        }
        composeTestRule.waitForIdle()
        assertFalse("before: nothing has applied the flag yet", hasKeepScreenOnFlag())

        showFocusScreen = true
        composeTestRule.waitForIdle()
        assertTrue("during: the DisposableEffect applied the flag", hasKeepScreenOnFlag())

        showFocusScreen = false
        composeTestRule.waitForIdle()
        assertFalse("after: onDispose reverted the flag", hasKeepScreenOnFlag())
    }

    @Test
    fun exitButton_staysReachable_whileImmersive_AC_V6() {
        val taskRepository = FakeFocusTaskRepository(listOf(pendingTask))
        val userPreferencesRepository = FakeUserPreferencesRepository(
            UserPreferences(
                focusSession = FocusSession(
                    startedAt = System.currentTimeMillis(),
                    exitCode = "1234",
                    roster = setOf(pendingTask.id),
                ),
                focusShieldOptions = FocusShieldOptions(keepScreenOn = true),
            ),
        )
        val viewModel = FocusViewModel(taskRepository, userPreferencesRepository, FakeFocusShieldController())

        composeTestRule.setContent {
            NeverLateTheme {
                FocusRoute(
                    viewModel = viewModel,
                    onSessionCompleted = {},
                    onSessionAbandoned = {},
                    widthSizeClass = WindowWidthSizeClass.Compact,
                )
            }
        }
        composeTestRule.waitForIdle()

        // Immersive is active (keepScreenOn = true implies it, see FocusRoute's KDoc) — the exit
        // action must still exist, be enabled and clickable: no control needed to leave the
        // session is ever hidden by the immersive state.
        composeTestRule.onNodeWithText(string(R.string.focus_exit_button)).assertExists().assertHasClickAction()
    }
}
