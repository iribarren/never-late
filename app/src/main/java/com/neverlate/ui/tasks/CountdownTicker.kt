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
 *
 * Feature 20b (compact remaining time): the countdown *text* dropped seconds and now only changes
 * once a minute (`2h 38m`), so this 1 s cadence is no longer justified by the text. It is kept
 * anyway — do not lower it or decouple it from the text refresh — because feature 19's task-card
 * progress bar (`deadlineProgressFor` → `animateFloatAsState`) still consumes this same tick to
 * drain **smoothly**; a minute-cadence tick would make the bar visibly lurch. See the feature 20b
 * spec's "CountdownTicker decision" section for the full rationale: the widget/notification are
 * not driven by this ticker at all (they refresh via WorkManager/writes and are already
 * minute-granular), and on the card the extra recompositions are cheap since the countdown `Text`
 * itself is unchanged 59 out of 60 ticks.
 */
fun countdownTicker(intervalMillis: Long = 1_000L): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(intervalMillis)
    }
}
