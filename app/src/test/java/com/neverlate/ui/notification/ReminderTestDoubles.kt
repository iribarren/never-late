package com.neverlate.ui.notification

import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [TaskRepository] fake shared by [ReminderSchedulingRepositoryTest] and
 * `com.neverlate.ui.settings.SettingsViewModelTest` — both only care about task storage as a
 * supporting actor for reminder behaviour, not the countdown/timer logic under test in
 * `com.neverlate.ui.tasks` (see that package's own fakes for that).
 *
 * Assigns a fresh sequential id to a brand-new task ([Task.id] == 0), mirroring
 * [com.neverlate.data.tasks.RoomTaskRepository], because
 * [ReminderSchedulingRepository.saveTask] specifically depends on getting back a real, non-zero
 * id for a task that has never been saved before — the reminder must be keyed by that id, not 0.
 */
class FakeTaskRepository(initialTasks: List<Task> = emptyList()) : TaskRepository {

    private val tasksFlow = MutableStateFlow(initialTasks)

    /** Every task this fake has been asked to save, in call order, *after* id assignment. */
    val savedTasks = mutableListOf<Task>()

    /** Every task id this fake has been asked to delete, in call order. */
    val deletedIds = mutableListOf<Long>()

    /** Every task id [startTimer] was called with, in call order — proves a decorator's
     *  pass-through method actually reached this fake. */
    val startedIds = mutableListOf<Long>()

    /** Every task id [pauseTimer] was called with, in call order. */
    val pausedIds = mutableListOf<Long>()

    override fun observeTasks(): Flow<List<Task>> = tasksFlow

    override fun observeTask(id: Long): Flow<Task?> = tasksFlow.map { tasks -> tasks.firstOrNull { it.id == id } }

    override suspend fun saveTask(task: Task): Long {
        val id = if (task.id == 0L) (tasksFlow.value.maxOfOrNull { it.id } ?: 0L) + 1 else task.id
        val saved = task.copy(id = id)
        savedTasks += saved
        tasksFlow.update { tasks ->
            if (task.id == 0L) tasks + saved else tasks.map { if (it.id == task.id) saved else it }
        }
        return id
    }

    override suspend fun deleteTask(id: Long) {
        deletedIds += id
        tasksFlow.update { tasks -> tasks.filterNot { it.id == id } }
    }

    override suspend fun startTimer(id: Long) {
        startedIds += id
    }

    override suspend fun pauseTimer(id: Long) {
        pausedIds += id
    }
}

/**
 * In-memory [ReminderScheduler] fake shared by [ReminderSchedulingRepositoryTest] and
 * `com.neverlate.ui.settings.SettingsViewModelTest` — both need to assert which task ids were
 * scheduled/cancelled, and (for the decorator) in what *order*, without a real
 * [android.app.AlarmManager], which needs an Android runtime to even instantiate.
 */
class FakeReminderScheduler : ReminderScheduler {

    /** One call recorded per [schedule]/[cancel] invocation, in call order. Exposed directly so
     *  tests can assert ordering (e.g. that a cancel always precedes its matching schedule, per
     *  [ReminderSchedulingRepository]'s "always cancel first" contract); [cancelledIds] and
     *  [scheduledCalls] are convenience views filtered by call type. */
    sealed class Call {
        data class Scheduled(val taskId: Long, val triggerAtMillis: Long) : Call()
        data class Cancelled(val taskId: Long) : Call()
    }

    val calls = mutableListOf<Call>()

    override fun schedule(taskId: Long, triggerAtMillis: Long) {
        calls += Call.Scheduled(taskId, triggerAtMillis)
    }

    override fun cancel(taskId: Long) {
        calls += Call.Cancelled(taskId)
    }

    /** Every task id [cancel] was called with, in call order. */
    val cancelledIds: List<Long> get() = calls.filterIsInstance<Call.Cancelled>().map { it.taskId }

    /** Every [Call.Scheduled] call, in call order. */
    val scheduledCalls: List<Call.Scheduled> get() = calls.filterIsInstance<Call.Scheduled>()
}
