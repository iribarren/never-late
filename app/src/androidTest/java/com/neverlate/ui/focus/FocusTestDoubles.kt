package com.neverlate.ui.focus

import com.neverlate.data.ThemeMode
import com.neverlate.data.UserPreferences
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.sync.SyncStatus
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.domain.tasks.FocusSession
import com.neverlate.domain.tasks.TaskListCriteria
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

/**
 * In-memory [TaskRepository] fake for Modo Foco instrumented tests (`docs/specs/2026-08-18-focus-mode.md`)
 * — same shape as [com.neverlate.ui.tasks.TasksRouteSnackbarTest]'s `NoopTaskRepository`, extended
 * just enough to actually apply a [saveTask] write, since [FocusExitAccessibilityTest] needs
 * completing a task to be observable both in the UI and via [savedTasks]. Promoted to this shared
 * file (rather than duplicated per test file) because two files in this package need it — the same
 * threshold `ReminderTestDoubles.kt` documents for its own promotion.
 */
internal class FakeFocusTaskRepository(initialTasks: List<Task> = emptyList()) : TaskRepository {
    private val tasksFlow = MutableStateFlow(initialTasks)

    /** Every [Task] this fake has been asked to [saveTask], in call order — lets a test assert
     *  "abandoning writes no task" (AC-23) as a direct, positive check. */
    val savedTasks = mutableListOf<Task>()

    override fun observeTasks(): Flow<List<Task>> = tasksFlow

    override fun observeTask(id: Long): Flow<Task?> = flowOf(tasksFlow.value.firstOrNull { it.id == id })

    override suspend fun saveTask(task: Task): Long {
        savedTasks.add(task)
        tasksFlow.update { tasks -> tasks.map { if (it.id == task.id) task else it } }
        return task.id
    }

    override suspend fun deleteTask(id: Long) = Unit
    override suspend fun startTimer(id: Long) = Unit
    override suspend fun pauseTimer(id: Long) = Unit
    override fun observeSyncStatus(): Flow<SyncStatus> = flowOf(SyncStatus.Idle)
}

/** In-memory [UserPreferencesRepository] fake seeded with an active [FocusSession] — the same
 *  "mutate the backing MutableStateFlow" pattern every hand-written fake in this codebase uses for
 *  [startFocusSession]/[endFocusSession] (see e.g. `SyncTestDoubles.kt`'s `FakeUserPreferencesRepository`). */
internal class FakeFocusUserPreferencesRepository(initial: UserPreferences) : UserPreferencesRepository {
    override val userPreferences = MutableStateFlow(initial)

    override suspend fun saveOnboarding(name: String) = Unit
    override suspend fun saveName(name: String) = Unit
    override suspend fun saveThemeMode(mode: ThemeMode) = Unit
    override suspend fun saveRemindersEnabled(enabled: Boolean) = Unit
    override suspend fun saveReminderLeadMinutes(minutes: Int) = Unit
    override suspend fun saveSyncCursor(cursor: Long) = Unit
    override suspend fun saveDynamicColor(enabled: Boolean) = Unit
    override suspend fun saveTaskListArrangement(criteria: TaskListCriteria) = Unit

    override suspend fun startFocusSession(session: FocusSession) {
        userPreferences.update { it.copy(focusSession = session) }
    }

    override suspend fun endFocusSession() {
        userPreferences.update { it.copy(focusSession = null) }
    }
}
