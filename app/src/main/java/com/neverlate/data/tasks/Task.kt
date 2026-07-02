package com.neverlate.data.tasks

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single task, persisted in the Room database (see [NeverLateDatabase]).
 *
 * `@Entity` + `@PrimaryKey` are this project's first use of Room: they tell the KSP-generated
 * code to create a `tasks` table with one column per constructor property, and to let SQLite
 * assign [id] automatically (`autoGenerate = true`) whenever a [Task] is inserted with the
 * default `id = 0`.
 *
 * The domain rule "must have at least [estimatedDurationMillis] or [deadline]" is enforced in
 * [com.neverlate.ui.tasks.TaskEditViewModel] (see `validateTaskForm`), not here: Room's schema
 * has no way to express "at least one of two nullable columns", so this class stays a plain data
 * holder and the rule lives with the rest of the form validation.
 *
 * Countdown state is derived from the **wall clock** rather than kept in memory, so it survives
 * process death (Android can kill and recreate the app's process at any time) and can later be
 * read by the widget (feature 05) and lock-screen notification (feature 06) without needing a
 * "live" timer object:
 * - While the countdown is **running**, [timerEndsAt] holds the wall-clock instant (epoch millis)
 *   at which it will reach zero, and [remainingMillis] is null.
 * - While **paused** (or never started), [timerEndsAt] is null; [remainingMillis] freezes how
 *   much time was left the last time it was paused, so resuming can recompute a fresh
 *   [timerEndsAt]. A task that has never been started keeps both fields null — see
 *   [computeRemainingMillis] for how "remaining time" is still derived in that case.
 */
@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val estimatedDurationMillis: Long? = null,
    val deadline: Long? = null,
    val timerEndsAt: Long? = null,
    val remainingMillis: Long? = null,
) {
    /**
     * True while the countdown is actively ticking down towards [timerEndsAt].
     *
     * A `get()`-only property like this has no backing field, so Room does not treat it as a
     * column — it is purely a convenience derived from [timerEndsAt].
     */
    val isRunning: Boolean
        get() = timerEndsAt != null
}
