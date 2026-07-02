package com.neverlate.data.tasks

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Text format used to both display and parse a deadline, e.g. "24/12/2026 20:30". */
private const val DEADLINE_PATTERN = "dd/MM/yyyy HH:mm"

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

/** Formats an estimated duration as a short label, e.g. "1 h 30 min" or "45 min". */
fun formatDurationLabel(millis: Long): String {
    val totalMinutes = millis / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "$hours h $minutes min"
        hours > 0 -> "$hours h"
        else -> "$minutes min"
    }
}

/**
 * Formats an epoch-millis deadline as a human-readable date and time, using [DEADLINE_PATTERN].
 * [parseDeadline] is this function's inverse, used to read the same format back from the edit
 * form.
 *
 * `java.time` (the more modern date/time API) needs Android API 26+ or core library
 * desugaring, neither of which this project has set up yet (`minSdk = 24`), so this project
 * still uses the older `java.text`/`java.util` date types.
 */
fun formatDeadline(epochMillis: Long): String =
    SimpleDateFormat(DEADLINE_PATTERN, Locale.getDefault()).format(Date(epochMillis))

/**
 * Parses a deadline typed in [DEADLINE_PATTERN] format back into epoch millis, or null if [text]
 * does not match that format. `isLenient = false` makes the parser reject nonsense dates (e.g.
 * "32/13/2026") instead of silently rolling them over into a nearby valid date.
 */
fun parseDeadline(text: String): Long? {
    val format = SimpleDateFormat(DEADLINE_PATTERN, Locale.getDefault()).apply { isLenient = false }
    return try {
        format.parse(text)?.time
    } catch (error: ParseException) {
        null
    }
}
