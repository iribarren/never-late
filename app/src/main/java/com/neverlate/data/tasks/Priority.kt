package com.neverlate.data.tasks

import kotlinx.serialization.Serializable

/**
 * How important the user considers a [Task] (feature 13b) — an app-level concept, distinct from the
 * *time* urgency the countdown derives from the deadline (feature 17). It lets a user flag which
 * tasks matter most independently of when they are due.
 *
 * Like [SyncState], this is an `enum` — a type Room has **no** built-in column type for — so it is
 * persisted through a `@TypeConverter` (see [com.neverlate.data.sync.Converters.fromPriority]):
 * Room stores [name] as `TEXT`. The constants are ordered least → most important, but nothing
 * relies on that ordinal: the converter stores the *name*, so reordering or inserting a constant
 * later never corrupts existing rows.
 *
 * [NONE] is the default for a brand-new task and, crucially, the value every pre-13b row is given
 * by the `MIGRATION_4_5` migration (`... DEFAULT 'NONE'`) — see [NeverLateDatabase].
 *
 * `@Serializable` lets it cross the wire directly inside [com.neverlate.data.sync.TaskDto]:
 * kotlinx.serialization encodes an enum by its constant [name] by default, which is exactly the
 * `"NONE"`/`"LOW"`/... string the API contract (§4) specifies.
 */
@Serializable
enum class Priority {
    /** No priority set — the default. Shows no indicator on the task list. */
    NONE,

    /** Low importance. */
    LOW,

    /** Medium importance. */
    MEDIUM,

    /** High importance — the most prominent indicator. */
    HIGH,
}
