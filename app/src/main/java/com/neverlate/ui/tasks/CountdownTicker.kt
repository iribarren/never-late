package com.neverlate.ui.tasks

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Emits [Unit] once, then again every [intervalMillis], forever — a "wake me up periodically"
 * signal used by [TasksViewModel] to refresh the displayed remaining time while at least one
 * countdown is running.
 *
 * It carries no time data of its own: [TasksViewModel] always recomputes remaining time from the
 * wall clock via [com.neverlate.data.tasks.computeRemainingMillis] on every tick, so a delayed or
 * dropped tick (e.g. while the app is backgrounded and coroutines are throttled) never produces
 * an incorrect value — only a slightly late UI refresh, exactly as the feature spec's "derive
 * from the wall clock" risk mitigation calls for.
 */
fun countdownTicker(intervalMillis: Long = 1_000L): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(intervalMillis)
    }
}
