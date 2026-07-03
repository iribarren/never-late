package com.neverlate.data.tasks

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Fixed, **locale-independent** text pattern used to both display *and* parse a deadline in the
 * edit form, e.g. "24/12/2026 20:30". It is bound to [Locale.ROOT] on purpose (see
 * [INPUT_FORMATTER]): the edit field is a machine round-trip, so it must read back exactly what it
 * wrote regardless of the device language. Locale-aware, human-facing date rendering is a
 * *separate* concern handled by [formatDeadlineForDisplay].
 */
private const val DEADLINE_INPUT_PATTERN = "dd/MM/yyyy HH:mm"

/**
 * Formatter for [DEADLINE_INPUT_PATTERN]. Pinned to [Locale.ROOT] so parsing is deterministic: a
 * deadline typed on a Spanish phone parses identically on an English one. The default resolver
 * style (SMART) rejects impossible dates such as "32/13/2026" instead of rolling them over.
 */
private val INPUT_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern(DEADLINE_INPUT_PATTERN, Locale.ROOT)

/**
 * How much time is left on [task]'s countdown at instant [now] (both in epoch milliseconds).
 *
 * This is a pure function — same inputs always produce the same output, with no clock or
 * database reads of its own — which is what makes both [RoomTaskRepository]'s start/pause logic
 * and [com.neverlate.ui.tasks.TasksViewModel]'s once-a-second tick fully unit-testable by simply
 * passing in different [Task]/`now` combinations, no fake clock or in-memory database needed.
 *
 * The countdown rule (approved 2026-07-02, see the feature spec's US-5): whenever [Task.deadline]
 * is set, remaining time always counts down towards it — [Task.estimatedDurationMillis] is purely
 * informational in that case. Only a duration-only task (no deadline) counts down for that
 * duration.
 */
fun computeRemainingMillis(task: Task, now: Long): Long {
    val raw = when {
        // Running: derive from the persisted wall-clock end instant, never from an in-memory
        // counter that a process restart could lose.
        task.timerEndsAt != null -> task.timerEndsAt - now
        // Paused: the value frozen at the moment of the last pause.
        task.remainingMillis != null -> task.remainingMillis
        // Never started, and there is a deadline: count down towards it regardless of duration.
        task.deadline != null -> task.deadline - now
        // Never started, duration-only.
        else -> task.estimatedDurationMillis ?: 0L
    }
    // Never show a negative countdown: zero means "time's up".
    return raw.coerceAtLeast(0L)
}

/** Formats a countdown as `mm:ss`, switching to `h:mm:ss` once it reaches an hour. */
fun formatRemaining(millis: Long): String {
    val totalSeconds = millis / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

/**
 * Splits an estimated duration into whole hours and the leftover minutes, e.g. 90 min → (1, 30).
 *
 * Kept as a pure, text-free helper so the *presentation* (unit labels, number formatting, word
 * order) lives in the UI layer with the string resources it needs — see how
 * [com.neverlate.ui.tasks.TaskRow] turns these numbers into a localized "1 h 30 min" label. That
 * separation is why this file can stay plain Kotlin (no Android `Context`) and unit-testable, and
 * why a translator can reorder units without touching code.
 */
fun durationParts(millis: Long): Pair<Long, Long> {
    val totalMinutes = millis / 60_000
    return (totalMinutes / 60) to (totalMinutes % 60)
}

/**
 * Formats an epoch-millis deadline for **human display**, using the given [locale]'s conventions
 * (day/month order, separators, 12h/24h) via a localized [DateTimeFormatter] style rather than a
 * hardcoded pattern. Callers pass the current configuration's locale so the text follows the
 * device language. This is intentionally *not* the inverse of [parseDeadline]: display text is
 * locale-shaped and not meant to be typed back in.
 */
fun formatDeadlineForDisplay(epochMillis: Long, locale: Locale): String {
    val formatter = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(locale)
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}

/**
 * Formats an epoch-millis deadline into the fixed [DEADLINE_INPUT_PATTERN] used to pre-fill the
 * edit form. [parseDeadline] is this function's exact inverse: what it writes, the parser reads
 * back, on any device locale.
 */
fun formatDeadlineForInput(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(INPUT_FORMATTER)

/**
 * Parses a deadline typed in [DEADLINE_INPUT_PATTERN] format back into epoch millis, or null if
 * [text] does not match that format. Because [INPUT_FORMATTER] uses the SMART resolver, nonsense
 * dates (e.g. "32/13/2026") are rejected instead of silently rolling over into a nearby valid date.
 */
fun parseDeadline(text: String): Long? =
    try {
        LocalDateTime.parse(text, INPUT_FORMATTER)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (error: DateTimeParseException) {
        null
    }
