package com.neverlate.ui.tasks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.neverlate.R
import com.neverlate.data.ThemeMode
import com.neverlate.data.UserPreferences
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.sync.SyncStatus
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.ui.theme.NeverLateTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import org.junit.Rule
import org.junit.Test

/** An empty, static [TaskRepository]: this test only cares about the Empty state's message. */
private class EmptyTaskRepository : TaskRepository {
    override fun observeTasks(): Flow<List<Task>> = flowOf(emptyList())
    override fun observeTask(id: Long): Flow<Task?> = flowOf(null)
    override suspend fun saveTask(task: Task): Long = 0L
    override suspend fun deleteTask(id: Long) = Unit
    override suspend fun startTimer(id: Long) = Unit
    override suspend fun pauseTimer(id: Long) = Unit
    override fun observeSyncStatus(): Flow<SyncStatus> = flowOf(SyncStatus.Idle)
}

// Reuses the private `NoopMotionSettings` already declared in TasksRouteSnackbarTest.kt would
// require making it non-private; simplest to keep this file self-contained instead, matching how
// each androidTest file in this package already declares its own small fakes.
private class EmptyStateNoopMotionSettings : com.neverlate.data.settings.MotionSettings {
    override val reduceMotion: Flow<Boolean> = flowOf(false)
}

/**
 * A **mutable** in-memory [UserPreferencesRepository] fake — unlike the `Noop*` fakes elsewhere in
 * this package, [userPreferences] here is a live [MutableStateFlow] a test can push new values
 * into, which is exactly the point: it lets this test simulate "the user just renamed themselves
 * in Settings" without going through [DataStoreUserPreferencesRepository][com.neverlate.data.DataStoreUserPreferencesRepository]
 * at all.
 */
private class MutableUserPreferencesRepository(
    initial: UserPreferences = UserPreferences(),
) : UserPreferencesRepository {
    override val userPreferences = MutableStateFlow(initial)
    override suspend fun saveOnboarding(name: String) = Unit
    override suspend fun saveName(name: String) = Unit
    override suspend fun saveThemeMode(mode: ThemeMode) = Unit
    override suspend fun saveRemindersEnabled(enabled: Boolean) = Unit
    override suspend fun saveReminderLeadMinutes(minutes: Int) = Unit
    override suspend fun saveSyncCursor(cursor: Long) = Unit
    override suspend fun saveDynamicColor(enabled: Boolean) = Unit
}

/**
 * editable-profile-name spec (US-3): proves the Tasks empty state's personalized message reflects
 * a name change **live**, with no remount of [TasksRoute] and no process restart — the instrumented
 * counterpart the spec explicitly asked for ("test instrumentado de que el cambio se refleja sin
 * reiniciar"). [TasksViewModel.userName] is `UserPreferencesRepository.userPreferences.map { it.name
 * }.stateIn(...)`, so pushing a new value into the same [MutableUserPreferencesRepository] instance
 * this composition was built with is the direct, minimal way to exercise that reactivity end to end
 * (ViewModel → Route → Screen), one level above what [TasksScreenTest] can reach by driving the
 * stateless [TasksScreen] with a fixed `userName` parameter.
 */
class TasksEmptyStatePersonalizationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(resId: Int): String = targetContext.getString(resId)
    private fun string(resId: Int, vararg formatArgs: Any): String = targetContext.getString(resId, *formatArgs)

    private fun waitForText(text: String) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun emptyState_reflectsANameChange_immediately_withNoRestart() {
        val userPreferencesRepository = MutableUserPreferencesRepository(UserPreferences(name = ""))

        composeTestRule.setContent {
            NeverLateTheme {
                TasksRoute(
                    viewModel = TasksViewModel(
                        repository = EmptyTaskRepository(),
                        motionSettings = EmptyStateNoopMotionSettings(),
                        userPreferencesRepository = userPreferencesRepository,
                    ),
                    onAddTaskClick = {},
                    onTaskClick = {},
                    onBack = {},
                )
            }
        }

        // D7: blank stored name falls back to the generic string, with no stray placeholder.
        waitForText(string(R.string.tasks_empty))
        composeTestRule.onNodeWithText(string(R.string.tasks_empty)).assertExists()
        composeTestRule.onNodeWithText(string(R.string.tasks_empty_personalized, "Ada")).assertDoesNotExist()

        // Simulate a rename landing from Settings: the same repository instance backing this
        // already-composed TasksRoute gets a new value, with no key()/remount involved.
        userPreferencesRepository.userPreferences.update { it.copy(name = "Ada") }

        // US-3: the empty state must pick up the new name live.
        waitForText(string(R.string.tasks_empty_personalized, "Ada"))
        composeTestRule.onNodeWithText(string(R.string.tasks_empty_personalized, "Ada")).assertExists()
        composeTestRule.onNodeWithText(string(R.string.tasks_empty)).assertDoesNotExist()
    }
}
