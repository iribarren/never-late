package com.neverlate.data.sync

/** Which HTTP verb an [OutboxEntity] row will replay against [TasksApi] when pushed. */
enum class OutboxOperation {
    CREATE,
    UPDATE,
    DELETE,
}
