package com.neverlate.ui.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.TaskRepository
import kotlinx.coroutines.flow.Flow

/**
 * Decorates [delegate] so that every write also tells [PendingTasksWidget] to redraw.
 *
 * [com.neverlate.ui.tasks.TasksViewModel] stays fresh by *observing* [TaskRepository.observeTasks]
 * — a `Flow` that emits again whenever the underlying rows change. A widget cannot do that: it
 * lives in the launcher's process, not the app's, and only ever redraws when explicitly told to
 * via `GlanceAppWidget.updateAll` (see [PendingTasksWidget.provideGlance]). There is no
 * cross-process `Flow` collection happening for free. This decorator is what closes that gap: it
 * wraps the real [TaskRepository] (see [com.neverlate.data.tasks.RoomTaskRepository]) without
 * changing it at all, and triggers a redraw right after each write that could change what the
 * widget shows.
 *
 * Decorating the interface — rather than, say, having every `ViewModel` call `updateAll` itself —
 * keeps this concern in one place and keeps the feature 04 `ViewModel`s unaware that a widget
 * exists at all.
 */
class WidgetRefreshingTaskRepository(
    private val delegate: TaskRepository,
    private val context: Context,
) : TaskRepository {

    override fun observeTasks(): Flow<List<Task>> = delegate.observeTasks()

    override fun observeTask(id: Long): Flow<Task?> = delegate.observeTask(id)

    override suspend fun saveTask(task: Task) {
        delegate.saveTask(task)
        refreshWidget()
    }

    override suspend fun deleteTask(id: Long) {
        delegate.deleteTask(id)
        refreshWidget()
    }

    override suspend fun startTimer(id: Long) {
        delegate.startTimer(id)
        refreshWidget()
    }

    override suspend fun pauseTimer(id: Long) {
        delegate.pauseTimer(id)
        refreshWidget()
    }

    private suspend fun refreshWidget() {
        // updateAll is a no-op (cheap) when the user has never placed the widget, so this never
        // needs to check "is a widget even on the home screen" first.
        PendingTasksWidget().updateAll(context)
    }
}
