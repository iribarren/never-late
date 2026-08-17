package com.neverlate.ui.tasks

import com.neverlate.data.tasks.Task
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The default, full-motion cadence: once a second — see [countdownTicker]'s KDoc for why. */
const val TICK_INTERVAL_MILLIS = 1_000L

/**
 * The reduced-motion cadence (`reduce-motion` spec, D4): once a minute, matching the countdown
 * *text*'s own granularity (`2h 38m`, feature 20b) so the text and the progress bar can never look
 * like they disagree. [tickIntervalFor] clamps this down when a running task is about to expire.
 */
const val REDUCED_MOTION_TICK_INTERVAL_MILLIS = 60_000L

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
 *
 * **Bounded exception (`reduce-motion` spec, D2), added without touching the paragraph above:**
 * that whole justification rests on one premise — that the progress bar can actually *drain
 * smoothly*. Under reduced motion (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`) it cannot:
 * `animateFloatAsState` has already collapsed to an instant snap, so there is no glide left to
 * protect. The 1 s cadence is therefore not being lowered or decoupled from the text in general —
 * it is *bounded* to exactly the condition its own justification depends on: "whenever the
 * progress bar can actually animate", which is always, except when the system has switched
 * animation off. See [tickIntervalFor] for the reduced-motion cadence this exception hands off to,
 * and `docs/specs/2026-08-17-reduce-motion.md` (D2) for the full reasoning.
 */
fun countdownTicker(intervalMillis: Long = TICK_INTERVAL_MILLIS): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(intervalMillis)
    }
}

/**
 * The pure cadence rule behind [countdownTicker]'s interval (`reduce-motion` spec, D4): a
 * function of "should motion be reduced", the current task list, and the current instant, with no
 * Android dependency, so it is JVM-unit-testable in isolation.
 *
 * With [reduceMotion] `false`, always returns [TICK_INTERVAL_MILLIS] — byte-for-byte the
 * pre-feature cadence.
 *
 * With [reduceMotion] `true`, returns [REDUCED_MOTION_TICK_INTERVAL_MILLIS], **clamped** down to
 * the number of milliseconds until the soonest running task's expiry (`timerEndsAt`) if that is
 * sooner. This is not cosmetic: [TasksViewModel.autoPauseTimedOut] reacts to a running task
 * reaching zero by writing to the database, and a flat one-minute cadence could delay that write
 * — and the "Tiempo agotado" state on screen — by up to 59 seconds, degrading something
 * *functional* under an accessibility mode, which must never happen. The result is always floored
 * at [TICK_INTERVAL_MILLIS], never below it, even when a task's expiry has already passed or is
 * right now — a `delay(0)` (or negative) loop would burn battery for nothing.
 */
fun tickIntervalFor(reduceMotion: Boolean, tasks: List<Task>, now: Long): Long {
    if (!reduceMotion) return TICK_INTERVAL_MILLIS

    val soonestExpiry = tasks
        .asSequence()
        .filter { it.isRunning }
        .mapNotNull { it.timerEndsAt }
        .minOrNull()
        ?: return REDUCED_MOTION_TICK_INTERVAL_MILLIS

    val millisUntilExpiry = soonestExpiry - now
    return millisUntilExpiry.coerceIn(TICK_INTERVAL_MILLIS, REDUCED_MOTION_TICK_INTERVAL_MILLIS)
}
