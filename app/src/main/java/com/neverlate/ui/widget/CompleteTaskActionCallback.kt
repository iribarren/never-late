package com.neverlate.ui.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.di.WidgetEntryPoint
import com.neverlate.ui.notification.TasksNotificationService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

/** The [ActionParameters.Key] a large-bucket row's `actionRunCallback` carries the tapped task's id in. */
val taskIdKey = ActionParameters.Key<Long>("com.neverlate.widget.TASK_ID")

/**
 * Completes a task from the large bucket of [PendingTasksWidget], without opening the app
 * (spec `widget-adaptive-layout`, US-3). `ActionCallback.onAction` is `suspend` because Glance
 * runs it off the launcher's UI thread and expects real work (here, a Room write through the
 * repository chain) to happen inline rather than being fired-and-forgotten.
 *
 * **D4 — why this reads [WidgetEntryPoint.taskRepository] and not the app's usual injected
 * repository:** the unqualified `TaskRepository` binding is
 * [TaskSurfacesRefreshingRepository][com.neverlate.ui.widget.TaskSurfacesRefreshingRepository],
 * whose `refreshSurfaces()` calls back into `PendingTasksWidget().updateAll(context)`. Writing
 * through that layer from *inside* a widget action would re-enter the widget:
 * write -> `refreshSurfaces()` -> `updateAll` -> `provideGlance` -> (next action) -> write -> ...
 * [WidgetEntryPoint] exposes the `@ReminderRepo`-qualified layer instead — see its KDoc — which
 * still goes through the outbox and reminder scheduling (so this write is indistinguishable from
 * one made in the app) but does **not** loop back into the widget on its own. This callback is
 * therefore responsible for refreshing the widget and notification itself, exactly once, below.
 */
class CompleteTaskActionCallback : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[taskIdKey] ?: return

        val repository = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            .taskRepository()

        completeTask(repository, taskId)

        // Explicit, single refresh (D4) — the writer decides whether to redraw, not a decorator.
        // `update(context, glanceId)` targets the exact instance that received the tap, rather
        // than `updateAll`, which would redraw every placed instance for a change local to one.
        PendingTasksWidget().update(context, glanceId)
        TasksNotificationService.refresh(context)
    }
}

/**
 * The actual write [CompleteTaskActionCallback.onAction] performs, pulled out as a standalone
 * `internal` function so it is unit-testable against a hand-written [TaskRepository] fake with no
 * [Context]/[GlanceId]/Hilt graph involved — see `CompleteTaskActionCallbackTest`.
 *
 * A [taskId] deleted between the row being drawn and the tap being handled resolves to `null`
 * here — nothing to complete, so this no-ops rather than crashing (spec US-3's explicit
 * requirement); [CompleteTaskActionCallback.onAction] still refreshes afterwards either way, so
 * the (now-stale) row disappears from the widget on the next redraw.
 */
internal suspend fun completeTask(repository: TaskRepository, taskId: Long) {
    val task = repository.observeTask(taskId).first()
    if (task != null) {
        repository.saveTask(task.copy(completedAt = System.currentTimeMillis()))
    }
}
