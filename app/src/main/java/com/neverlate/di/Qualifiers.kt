package com.neverlate.di

import javax.inject.Qualifier

/**
 * Feature 13d: the [com.neverlate.data.tasks.TaskRepository] seam has **four** things that satisfy
 * the same type — the real, Room-backed repository plus its three decorators — so Hilt cannot tell
 * them apart from the type alone the way it can for every other interface in this project (one
 * interface, one implementation). These three qualifiers mark the three *inner* layers; the
 * outermost layer ([com.neverlate.ui.widget.TaskSurfacesRefreshingRepository]) is deliberately left
 * **unqualified** in `RepositoryModule`, since that is the one binding the rest of the app (every
 * ViewModel, [com.neverlate.MainActivity]) actually injects.
 *
 * Reading `RepositoryModule`'s four `TaskRepository` providers top to bottom — `RoomRepo` ->
 * `OutboxRepo` -> `ReminderRepo` -> unqualified — mirrors the nested constructor calls
 * `MainActivity.onCreate` used to write by hand (see its "before" KDoc), just spelled out as one
 * qualifier per nesting level instead of one line of indentation per level.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RoomRepo

/** Marks [com.neverlate.data.sync.OutboxTaskRepository] — wraps [RoomRepo]. See [RoomRepo]'s KDoc. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OutboxRepo

/** Marks [com.neverlate.ui.notification.ReminderSchedulingRepository] — wraps [OutboxRepo]. See [RoomRepo]'s KDoc. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ReminderRepo
