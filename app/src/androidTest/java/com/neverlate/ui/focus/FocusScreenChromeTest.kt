package com.neverlate.ui.focus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.neverlate.data.FakeUserPreferencesRepository
import com.neverlate.data.UserPreferences
import com.neverlate.data.tasks.Task
import com.neverlate.domain.tasks.FocusSession
import com.neverlate.ui.theme.NeverLateTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * AC-11: the Focus destination renders no [androidx.compose.material3.NavigationBar] and no
 * [androidx.compose.material3.NavigationRail] — both expose their items with `Role.Tab` semantics,
 * so scanning the whole tree for that role is a reliable, implementation-agnostic way to prove
 * neither is present.
 *
 * **What this test covers, and what it does not.** [FocusScreen] itself never conditionally renders
 * either component at any width — the reason the app-level chrome disappears on this destination is
 * that `Routes.FOCUS` is deliberately absent from `AppNavHost.kt`'s `TOP_LEVEL_ROUTES` (D4), which
 * gates `MainBottomBar`/`MainNavigationRail` in `MainAppNavHost`. That composable (and
 * `TOP_LEVEL_ROUTES` itself) is `private` with no existing instrumented-test seam reaching the full
 * navigation graph (see e.g. [com.neverlate.ui.tasks.TasksRouteSnackbarTest], which — like every
 * other instrumented test in this codebase — drives one Route directly rather than the whole
 * `AppNavHost`). This test therefore proves the necessary screen-level half: [FocusScreen] itself
 * introduces no such chrome at either width — a real regression guard (a future edit that
 * accidentally added a bottom bar to this screen would fail it). [FocusScreen]'s `widthSizeClass`
 * parameter is exercised here too (it does have a structural effect — see AC-V10, which
 * `ReadableWidthContainer`s just the row list, not the whole screen), even though *that* effect is
 * not what this particular test asserts.
 */
class FocusScreenChromeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val pendingTask = Task(id = 1L, title = "Preparar la presentación")

    private fun setFocusScreen(widthDp: Int, widthSizeClass: WindowWidthSizeClass) {
        val taskRepository = FakeFocusTaskRepository(listOf(pendingTask))
        val userPreferencesRepository = FakeUserPreferencesRepository(
            UserPreferences(
                focusSession = FocusSession(
                    startedAt = System.currentTimeMillis(),
                    exitCode = "1234",
                    roster = setOf(pendingTask.id),
                ),
            ),
        )
        val viewModel = FocusViewModel(taskRepository, userPreferencesRepository)

        composeTestRule.setContent {
            NeverLateTheme {
                Box(modifier = Modifier.width(widthDp.dp).fillMaxHeight()) {
                    FocusRoute(
                        viewModel = viewModel,
                        onSessionCompleted = {},
                        onSessionAbandoned = {},
                        widthSizeClass = widthSizeClass,
                    )
                }
            }
        }
    }

    private fun assertNoTabRoleNodes() {
        val tabRoleMatcher = SemanticsMatcher("has Role.Tab") { node ->
            SemanticsProperties.Role in node.config && node.config[SemanticsProperties.Role] == Role.Tab
        }
        val tabNodes = composeTestRule.onAllNodes(tabRoleMatcher, useUnmergedTree = true).fetchSemanticsNodes()

        assertTrue(
            "expected no NavigationBar/NavigationRail item (Role.Tab) on the Focus screen, found ${tabNodes.size}",
            tabNodes.isEmpty(),
        )
    }

    @Test
    fun compactWidth_noNavigationBarOrRailItems() {
        setFocusScreen(widthDp = 400, widthSizeClass = WindowWidthSizeClass.Compact)

        assertNoTabRoleNodes()
    }

    @Test
    fun expandedWidth_noNavigationBarOrRailItems() {
        setFocusScreen(widthDp = 1200, widthSizeClass = WindowWidthSizeClass.Expanded)

        assertNoTabRoleNodes()
    }
}
