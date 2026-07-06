package com.neverlate.data.tasks

/**
 * Where a [Task] row stands with respect to the backend (feature 11) — additive sync metadata on
 * top of the plain local model feature 04 introduced.
 *
 * Persisted via [com.neverlate.data.sync.Converters] (a `@TypeConverter`, this project's first —
 * Room has no built-in column type for an enum, so the converter tells it to store/read [name]
 * as plain text instead).
 */
enum class SyncState {
    /** The server has this row's current content; there is nothing queued in the outbox for it. */
    SYNCED,

    /** Created locally but never yet acknowledged by a successful `POST /tasks`. */
    PENDING_CREATE,

    /** Edited locally since the last successful sync; a `PATCH` is queued. */
    PENDING_UPDATE,

    /** Deleted locally (tombstoned, see [Task.deleted]); a `DELETE` is queued. */
    PENDING_DELETE,
}
