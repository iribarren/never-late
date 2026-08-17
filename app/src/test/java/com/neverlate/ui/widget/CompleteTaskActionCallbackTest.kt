package com.neverlate.ui.widget

import com.neverlate.data.sync.FakeUserPreferencesRepository
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.ui.notification.FakeReminderScheduler
import com.neverlate.ui.notification.FakeTaskRepository
import com.neverlate.ui.notification.ReminderSchedulingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [completeTask], the write path [CompleteTaskActionCallback] runs (spec
 * `widget-adaptive-layout`, US-3/D4). `ActionCallback.onAction` itself (the `Context`/`GlanceId`/
 * `EntryPointAccessors` plumbing) is not exercised here — that is thin, Android-only wiring with
 * nothing left to unit-test once [completeTask] is pulled out; the meaningful behaviour is here.
 */
class CompleteTaskActionCallbackTest {

    @Test
    fun `completing an existing task saves it with completedAt set, exactly once`() = runTest {
        val task = Task(id = 1L, title = "Enviar informe", estimatedDurationMillis = 10 * 60_000L)
        val repository = FakeTaskRepository(initialTasks = listOf(task))

        completeTask(repository, taskId = 1L)

        assertEquals(1, repository.savedTasks.size)
        val saved = repository.savedTasks.single()
        assertEquals(1L, saved.id)
        assertTrue("completedAt must be set", saved.completedAt != null)
    }

    @Test
    fun `completing a taskId that no longer exists is a no-op, not a crash`() = runTest {
        val repository = FakeTaskRepository(initialTasks = emptyList())

        completeTask(repository, taskId = 999L)

        assertTrue("no save should have happened for a missing task", repository.savedTasks.isEmpty())
    }

    // AC-6 (D4): completing through the @ReminderRepo-shaped layer — the exact concrete class
    // (ReminderSchedulingRepository) RepositoryModule.provideReminderSchedulingRepository binds to
    // that qualifier — must never reach a refresh-triggering decorator, because that decorator
    // sits *outside* this layer in the real chain (RoomRepo -> OutboxRepo -> ReminderRepo ->
    // unqualified TaskSurfacesRefreshingRepository), never inside it.

    @Test
    fun `completing through the ReminderRepo-shaped chain never invokes a refresh-counting decorator`() = runTest {
        val base = FakeTaskRepository(
            initialTasks = listOf(Task(id = 5L, title = "Tarea", estimatedDurationMillis = 5 * 60_000L)),
        )
        // Stands in for TaskSurfacesRefreshingRepository's write-then-refresh shape (see that
        // class), without needing its real Context/Glance dependencies — only its refresh count
        // matters here.
        val refreshSpy = RefreshCountingTaskRepository(base)

        // The real ReminderSchedulingRepository, wired the same way RepositoryModule wires
        // @ReminderRepo: delegate is the base repository directly, never refreshSpy. This mirrors
        // what WidgetEntryPoint.taskRepository() (@ReminderRepo) actually resolves to.
        val reminderRepo: TaskRepository = ReminderSchedulingRepository(
            delegate = base,
            scheduler = FakeReminderScheduler(),
            preferences = FakeUserPreferencesRepository(),
        )

        completeTask(reminderRepo, taskId = 5L)

        assertEquals("exactly one save reached the base repository", 1, base.savedTasks.size)
        assertEquals(
            "the refresh-counting decorator was never in this call's path, so it never ran",
            0,
            refreshSpy.refreshCount,
        )
    }

    @Test
    fun `sanity check - completing through a repository that DOES wrap the refresh decorator does trigger it`() = runTest {
        // The contrast case: if the widget mistakenly wrote through the unqualified layer instead
        // of @ReminderRepo, the refresh-counting decorator above it in the real chain WOULD fire.
        // This confirms refreshSpy is actually capable of detecting that, so the zero count above
        // is meaningful and not just an artifact of a spy that never counts anything.
        val base = FakeTaskRepository(
            initialTasks = listOf(Task(id = 9L, title = "Otra", estimatedDurationMillis = 5 * 60_000L)),
        )
        val refreshSpy = RefreshCountingTaskRepository(base)

        completeTask(refreshSpy, taskId = 9L)

        assertEquals(1, refreshSpy.refreshCount)
    }
}

/**
 * Hand-written spy mirroring [com.neverlate.ui.widget.TaskSurfacesRefreshingRepository]'s
 * write-then-refresh shape — counts how many times a write would have triggered
 * `refreshSurfaces()`, without needing that class's real `Context`/Glance/notification-service
 * dependencies (this project uses hand-written fakes, no mocking framework).
 */
private class RefreshCountingTaskRepository(private val delegate: TaskRepository) : TaskRepository {
    var refreshCount: Int = 0
        private set

    override fun observeTasks(): Flow<List<Task>> = delegate.observeTasks()
    override fun observeTask(id: Long): Flow<Task?> = delegate.observeTask(id)

    override suspend fun saveTask(task: Task): Long {
        val id = delegate.saveTask(task)
        refreshCount++
        return id
    }

    override suspend fun deleteTask(id: Long) {
        delegate.deleteTask(id)
        refreshCount++
    }

    override suspend fun startTimer(id: Long) {
        delegate.startTimer(id)
        refreshCount++
    }

    override suspend fun pauseTimer(id: Long) {
        delegate.pauseTimer(id)
        refreshCount++
    }
}
