package com.neverlate.data.sync

import androidx.room.TypeConverter
import com.neverlate.data.tasks.Priority
import com.neverlate.data.tasks.SyncState

/**
 * This project's first Room `@TypeConverter`s, registered on [com.neverlate.data.tasks.NeverLateDatabase]
 * via `@TypeConverters(Converters::class)`. Room only knows how to persist a fixed set of column
 * types (numbers, strings, booleans, byte arrays...) — an enum like [SyncState] or
 * [OutboxOperation] is not one of them, so without a converter Room would refuse to compile the
 * `@Entity` classes that use them ([com.neverlate.data.tasks.Task], [OutboxEntity]).
 *
 * Each pair of methods below is symmetric: one direction runs when Room *writes* a row (Kotlin
 * enum -> the `TEXT` it stores), the other when it *reads* one back (`TEXT` -> Kotlin enum).
 * Storing [Enum.name] (rather than, say, [Enum.ordinal]) keeps the stored value human-readable in
 * a database inspector and resilient to reordering the enum's constants later.
 */
class Converters {
    @TypeConverter
    fun fromSyncState(value: SyncState): String = value.name

    @TypeConverter
    fun toSyncState(value: String): SyncState =
        // Tolerant parsing, same reasoning as ThemeMode.fromStorage: a value this app no longer
        // recognises (e.g. after a future rename) must never crash a read, just fall back to a
        // safe default.
        SyncState.entries.firstOrNull { it.name == value } ?: SyncState.SYNCED

    @TypeConverter
    fun fromOutboxOperation(value: OutboxOperation): String = value.name

    @TypeConverter
    fun toOutboxOperation(value: String): OutboxOperation =
        OutboxOperation.entries.firstOrNull { it.name == value } ?: OutboxOperation.UPDATE

    // [Priority] (feature 13b): the same tolerant name<->TEXT pattern as the two enums above. The
    // fallback to NONE also matches how an unknown/absent priority is handled on the wire (see the
    // API contract §5), so a value this app no longer recognises degrades safely instead of crashing.
    @TypeConverter
    fun fromPriority(value: Priority): String = value.name

    @TypeConverter
    fun toPriority(value: String): Priority =
        Priority.entries.firstOrNull { it.name == value } ?: Priority.NONE
}
