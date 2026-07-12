package com.neverlate.ui.tasks

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.neverlate.R
import com.neverlate.data.sync.SyncStatus
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.ui.theme.NeverLateTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A minimal in-memory [TaskRepository]: [TasksRoute] needs a [TasksViewModel] built from one, but
 * this test only exercises the "task created" snackbar plumbing (US-3), not task CRUD — an empty,
 * static list is enough, same spirit as the other fakes under
 * `com.neverlate.ui.notification`/`com.neverlate.ui.tasks` test sources (not reused directly here
 * since those live in the `test` source set, unreachable from `androidTest`).
 *
 * Feature 13d: [TasksRoute] no longer takes a `taskRepository` parameter (Hilt injects
 * [TasksViewModel] via `hiltViewModel()` in production) — this test constructs [TasksViewModel]
 * directly instead, passing it through the Route's `viewModel` parameter. `@HiltViewModel`'s
 * `@Inject constructor` is still a perfectly ordinary public constructor, so this needs no Hilt
 * test infrastructure (`HiltAndroidRule`/`HiltTestApplication`) at all.
 */
private class NoopTaskRepository : TaskRepository {
    override fun observeTasks(): Flow<List<Task>> = flowOf(emptyList())
    override fun observeTask(id: Long): Flow<Task?> = flowOf(null)
    override suspend fun saveTask(task: Task): Long = 0L
    override suspend fun deleteTask(id: Long) = Unit
    override suspend fun startTimer(id: Long) = Unit
    override suspend fun pauseTimer(id: Long) = Unit
    override fun observeSyncStatus(): Flow<SyncStatus> = flowOf(SyncStatus.Idle)
}

/**
 * Covers feature 17's US-3 one-shot "task created" snackbar at the level it actually lives:
 * [TasksRoute]'s `LaunchedEffect` collecting [TASK_CREATED_RESULT_KEY] off a [SavedStateHandle]
 * (see that composable's KDoc). [TasksScreenTest] only drives the stateless [TasksScreen], which
 * has no notion of the one-shot signal at all, so this test goes one level up instead of feeding
 * a fake signal into a screen that doesn't consume it.
 *
 * The "does not replay" half is verified by fully remounting [TasksRoute] (via a [key] wrapper)
 * with the *same* [SavedStateHandle] instance, simulating what happens across a configuration
 * change: [androidx.lifecycle.SavedStateHandle.getStateFlow] re-emits its current stored value to
 * a brand-new collector, so if the flag had not been consumed (written back to `false`) the moment
 * it was shown, this remount would show a second snackbar with no new "task created" event.
 */
class TasksRouteSnackbarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun snackbarMessage(): String = targetContext.getString(R.string.tasks_task_created_snackbar)

    @Test
    fun taskCreatedFlag_showsSnackbarOnce_andIsConsumedSoARemountDoesNotReplayIt() {
        val handle = SavedStateHandle()
        val remountKey = mutableStateOf(0)

        composeTestRule.setContent {
            NeverLateTheme {
                key(remountKey.value) {
                    TasksRoute(
                        viewModel = TasksViewModel(NoopTaskRepository()),
                        onAddTaskClick = {},
                        onTaskClick = {},
                        onBack = {},
                        taskCreatedHandle = handle,
                    )
                }
            }
        }

        // Nothing created yet: no snackbar.
        composeTestRule.onNodeWithText(snackbarMessage()).assertDoesNotExist()

        // Simulate AppNavHost writing "a task was just created" onto this back stack entry.
        composeTestRule.runOnUiThread { handle[TASK_CREATED_RESULT_KEY] = true }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText(snackbarMessage())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText(snackbarMessage()).assertExists()

        // The flag must already be consumed (written back to false) as part of showing it, not
        // left true for a later collector to see again.
        assertEquals(false, handle.get<Boolean>(TASK_CREATED_RESULT_KEY))

        // Remount TasksRoute from scratch with the same handle, simulating a configuration change.
        // Its LaunchedEffect starts a fresh collection of the same SavedStateHandle flow; since the
        // stored value is already false, no new snackbar should appear.
        composeTestRule.runOnUiThread { remountKey.value++ }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(snackbarMessage()).assertDoesNotExist()
    }

    @Test
    fun taskCreatedFlag_firesAgainOnASubsequentGenuineCreation() {
        val handle = SavedStateHandle()

        composeTestRule.setContent {
            NeverLateTheme {
                TasksRoute(
                    viewModel = TasksViewModel(NoopTaskRepository()),
                    onAddTaskClick = {},
                    onTaskClick = {},
                    onBack = {},
                    taskCreatedHandle = handle,
                )
            }
        }

        composeTestRule.runOnUiThread { handle[TASK_CREATED_RESULT_KEY] = true }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText(snackbarMessage())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertTrue(handle.get<Boolean>(TASK_CREATED_RESULT_KEY) == false)

        // A second, genuine "task created" event (same live composition, no remount) must be able
        // to fire the snackbar again -- this is a one-shot-per-creation signal, not a one-shot-ever
        // latch.
        composeTestRule.runOnUiThread { handle[TASK_CREATED_RESULT_KEY] = true }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText(snackbarMessage())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText(snackbarMessage()).assertExists()
    }
}
