package com.neverlate.data.sync

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One pending local change waiting to be pushed to the backend — the "cola de salida" (outbox)
 * from the feature spec's *Sync Model*.
 *
 * [taskLocalId] is the `@PrimaryKey`, **not** an autoincrement id: this project's outbox keeps at
 * most **one** row per task, holding only its latest pending intent. A task edited twice before
 * the first edit is ever pushed does not need two queued `PATCH`es replayed in order — only the
 * final state matters — so a second [com.neverlate.data.sync.OutboxTaskRepository] write simply
 * replaces the earlier row ([androidx.room.OnConflictStrategy.REPLACE], see [OutboxDao.enqueue]).
 * This is a deliberate simplification over a full append-only change log: it is far simpler to
 * reason about and test, at the cost of not preserving intermediate history the server never
 * needed anyway.
 *
 * [clientRef] is generated once per task (the first time it is queued) and reused across
 * replaces/retries, so a repeated `POST /tasks` after a lost ack is idempotent (see the API
 * contract's §4). It is unused by `PATCH`/`DELETE` but kept on every row for a uniform schema.
 */
@Entity(tableName = "task_outbox")
data class OutboxEntity(
    @PrimaryKey val taskLocalId: Long,
    val operation: OutboxOperation,
    val clientRef: String,
    val retryCount: Int = 0,
    val enqueuedAt: Long,
)
