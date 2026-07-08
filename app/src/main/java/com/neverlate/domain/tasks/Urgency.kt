package com.neverlate.domain.tasks

/**
 * How urgently a task's countdown deserves the user's attention right now, from calmest to most
 * urgent. Feature 17 maps each level to a color in [com.neverlate.ui.tasks.TaskRow] (see that
 * file's `colorForUrgency`) — this file only decides *which* level applies, kept as plain Kotlin
 * so the thresholds are unit-testable on the JVM, the same split [ReminderPlanning.kt] already
 * uses for reminder scheduling.
 */
enum class UrgencyLevel { Calm, Soon, Urgent, Overdue }

/** Remaining time at or below which a still-ticking task counts as [UrgencyLevel.Urgent]. */
private const val URGENT_THRESHOLD_MILLIS = 5 * 60_000L

/** Remaining time at or below which a still-ticking task counts as [UrgencyLevel.Soon]. */
private const val SOON_THRESHOLD_MILLIS = 60 * 60_000L

/**
 * Derives a task's [UrgencyLevel] from the exact same `remainingMillis`/`isTimedOut` pair its
 * countdown text already reads (see [com.neverlate.data.tasks.computeRemainingMillis] and
 * [com.neverlate.ui.tasks.TaskUiModel]) — a pure function of those two values, with no clock read
 * of its own, so a test can simply pass in different combinations with no fake clock needed.
 *
 * Thresholds (feature 17 spec, approved 2026-07-08):
 * - [UrgencyLevel.Overdue] whenever [isTimedOut] is true, regardless of [remainingMillis].
 * - [UrgencyLevel.Urgent] at 5 minutes or less remaining (and not yet timed out).
 * - [UrgencyLevel.Soon] at 60 minutes or less remaining (and more than 5).
 * - [UrgencyLevel.Calm] otherwise (more than 60 minutes remaining).
 */
fun urgencyLevelFor(remainingMillis: Long, isTimedOut: Boolean): UrgencyLevel = when {
    isTimedOut -> UrgencyLevel.Overdue
    remainingMillis <= URGENT_THRESHOLD_MILLIS -> UrgencyLevel.Urgent
    remainingMillis <= SOON_THRESHOLD_MILLIS -> UrgencyLevel.Soon
    else -> UrgencyLevel.Calm
}
