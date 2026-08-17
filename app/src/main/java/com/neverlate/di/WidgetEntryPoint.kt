package com.neverlate.di

import com.neverlate.data.tasks.TaskRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Lets [com.neverlate.ui.widget.PendingTasksWidget] reach the Hilt graph even though Hilt never
 * constructs it. Every other consumer of [TaskRepository] is either `@AndroidEntryPoint`-annotated
 * (`MainActivity`) or itself constructed by Hilt (a `ViewModel`), so a plain `@Inject` field works.
 * A `GlanceAppWidget` is neither: it is instantiated directly by our own code —
 * `PendingTasksWidgetReceiver`, [com.neverlate.ui.widget.TaskSurfacesRefreshingRepository], and
 * `TaskSurfacesRefreshWorker` all call `PendingTasksWidget()` — so there is no Hilt-managed
 * constructor call for `@Inject` to hook into, and `@AndroidEntryPoint` doesn't apply either since
 * `GlanceAppWidget` isn't one of the Android component types Hilt knows how to intercept. An
 * `@EntryPoint`, resolved with [dagger.hilt.android.EntryPointAccessors.fromApplication] from
 * *inside* `provideGlance`, is the escape hatch for exactly this shape: none of the three call sites
 * above need to change, because the graph is reached where the widget itself runs, not where it is
 * constructed.
 *
 * **Why [ReminderRepo] and not the unqualified [TaskRepository] binding:** the unqualified binding
 * (see `RepositoryModule.provideTaskRepository`) is
 * [com.neverlate.ui.widget.TaskSurfacesRefreshingRepository], whose private `refreshSurfaces()`
 * calls `PendingTasksWidget().updateAll(context)` and
 * `com.neverlate.ui.notification.TasksNotificationService.refresh(context)` after every write. If
 * this entry point exposed that layer, a *read* today is harmless (`observeTasks()` is delegated
 * unchanged all the way down), but the moment the widget also *writes* through it — which is exactly
 * what row actions in a future feature would need — the call chain becomes write -> `refreshSurfaces()`
 * -> `updateAll` -> `provideGlance` -> write -> ..., the widget reentering itself. [ReminderRepo] is
 * the outermost layer in the decorator chain (`RoomRepo` -> `OutboxRepo` -> `ReminderRepo` ->
 * unqualified, see `Qualifiers.kt`) that does **not** loop back into the widget: reading through it
 * gives the exact same data (every layer between it and `RoomRepo` delegates `observeTasks()`
 * unchanged), and a future write through it still goes through the outbox and reminder scheduling
 * (what should happen) without silently re-triggering a surface refresh (what should not — the
 * writer, not this decorator, gets to decide whether to redraw the widget). Do not widen this to the
 * unqualified binding without re-reading this KDoc and
 * `docs/specs/2026-08-17-widget-hilt-color-token.md` (D2).
 *
 * **Deliberately reusable, not yet reused:** this same mechanism is the fix for every other place
 * that hand-builds `NeverLateDatabase.getInstance(...)` + `RoomTaskRepository(...)` instead of going
 * through Hilt — `TaskSurfacesRefreshWorker`, `BootRescheduleWorker`, `SyncWorker`,
 * `com.neverlate.ui.notification.TasksNotificationService`, and
 * `com.neverlate.ui.notification.ReminderReceiver`. Migrating them is out of scope for this feature.
 * When it happens, extend *this* entry point (add an accessor) rather than inventing a second one —
 * but check first which layer each of them actually needs: several of them write, and may need a
 * different qualifier than the widget's read-only [ReminderRepo].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {

    @ReminderRepo
    fun taskRepository(): TaskRepository
}
