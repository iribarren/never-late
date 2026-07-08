package com.neverlate.domain.tasks

/**
 * Feature 19's second pure helper alongside [Urgency.kt]'s `urgencyLevelFor` — same split as
 * [ReminderPlanning.kt]: a plain function of already-computed values, no clock read of its own, so
 * a JVM test can simply pass numbers in.
 *
 * This is the tutorial's first **determinate progress indicator**: a `LinearProgressIndicator`
 * takes a `0f..1f` "how much is done" value (as opposed to an *indeterminate* one, which just spins
 * to say "something is happening, duration unknown"). Here, "done" means "elapsed toward the
 * deadline" — the complement of the countdown's remaining time.
 *
 * The one thing this fraction needs that the countdown alone doesn't have is a **total window** to
 * measure elapsed time against. The data model has no `createdAt`/start anchor (see the feature 19
 * spec, Risk 1) — only [com.neverlate.data.tasks.Task.estimatedDurationMillis] — so that duration
 * doubles as the total window. A task with no usable duration has no well-defined "percent elapsed",
 * so it gets no bar at all: showing an arbitrary fill would be more misleading than showing none.
 *
 * @param remainingMillis time left, exactly as read by the countdown text and [urgencyLevelFor].
 * @param totalMillis the task's total window ([com.neverlate.data.tasks.Task.estimatedDurationMillis]);
 *   `null` or `<= 0` means there is no meaningful window to measure elapsed time against.
 * @param isTimedOut whether the task's countdown has already run out.
 * @return the elapsed fraction clamped to `0f..1f`, or `null` when [totalMillis] gives no
 *   meaningful window (the caller renders no bar in that case).
 */
fun deadlineProgressFor(remainingMillis: Long, totalMillis: Long?, isTimedOut: Boolean): Float? {
    if (totalMillis == null || totalMillis <= 0) return null
    if (isTimedOut || remainingMillis <= 0) return 1f
    if (remainingMillis >= totalMillis) return 0f

    val elapsedMillis = totalMillis - remainingMillis
    return (elapsedMillis.toFloat() / totalMillis.toFloat()).coerceIn(0f, 1f)
}
