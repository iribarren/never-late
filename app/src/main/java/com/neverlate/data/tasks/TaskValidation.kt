package com.neverlate.data.tasks

/**
 * Every way [validateTaskForm] can reject a task form. The screen (a `@Composable`, see
 * `com.neverlate.ui.tasks.TaskEditScreen`) maps each value to a localized message in
 * `strings.xml` — this file itself stays free of user-facing text, matching the project
 * convention that all display strings live as resources, not string literals in Kotlin.
 */
enum class TaskValidationError {
    BLANK_TITLE,
    INVALID_DURATION,
    INVALID_DEADLINE_FORMAT,
    MISSING_DURATION_OR_DEADLINE,
}

/**
 * The outcome of validating a task creation/edit form: either the parsed, ready-to-save values,
 * or the first problem found. A sealed interface (rather than, say, a nullable pair) forces every
 * caller to handle both cases explicitly via `when`.
 */
sealed interface TaskFormResult {
    data class Valid(val title: String, val durationMillis: Long?, val deadlineMillis: Long?) : TaskFormResult
    data class Invalid(val error: TaskValidationError) : TaskFormResult
}

/**
 * Validates and parses raw task-form input (US-1/US-3 of the feature spec): [title] must be
 * non-blank, and at least one of a valid duration (given as [durationHoursText] +
 * [durationMinutesText], see [parseDuration]) or [deadlineText] must be present.
 *
 * This function is pure (no ViewModel, no repository, no Android framework calls beyond
 * [SimpleDateFormat][java.text.SimpleDateFormat] parsing, which runs fine on a plain JVM test) so
 * it is the natural place to unit-test every combination of the form's validation rules.
 */
fun validateTaskForm(
    title: String,
    durationHoursText: String,
    durationMinutesText: String,
    deadlineText: String,
): TaskFormResult {
    val trimmedTitle = title.trim()
    if (trimmedTitle.isBlank()) return TaskFormResult.Invalid(TaskValidationError.BLANK_TITLE)

    val trimmedHoursText = durationHoursText.trim()
    val trimmedMinutesText = durationMinutesText.trim()
    val durationMillis: Long? = if (trimmedHoursText.isEmpty() && trimmedMinutesText.isEmpty()) {
        null
    } else {
        parseDuration(trimmedHoursText, trimmedMinutesText)
            ?: return TaskFormResult.Invalid(TaskValidationError.INVALID_DURATION)
    }

    val trimmedDeadlineText = deadlineText.trim()
    val deadlineMillis: Long? = if (trimmedDeadlineText.isEmpty()) {
        null
    } else {
        parseDeadline(trimmedDeadlineText) ?: return TaskFormResult.Invalid(TaskValidationError.INVALID_DEADLINE_FORMAT)
    }

    if (durationMillis == null && deadlineMillis == null) {
        return TaskFormResult.Invalid(TaskValidationError.MISSING_DURATION_OR_DEADLINE)
    }

    return TaskFormResult.Valid(title = trimmedTitle, durationMillis = durationMillis, deadlineMillis = deadlineMillis)
}

/**
 * Parses an hours field + a minutes field into total milliseconds, or null if the combination is
 * invalid. Kept separate from [validateTaskForm] so the "what counts as a valid duration" rule has
 * a single, independently testable home.
 *
 * Rules (see the feature spec's Edge case matrix): an empty field counts as 0. Each field is
 * parsed with [toLongOrNull], which already rejects non-numeric text, and returns null on
 * overflow. Minutes need not be under 60 — only the *total* matters (`0 h 90 min` normalizes to
 * 90 min), so hours and minutes are not validated independently. The combined total minutes must
 * be strictly positive: a zero total (e.g. `0`/`0`) is not a valid explicit duration — leaving both
 * fields empty is how "no duration" is expressed instead. Both the hours→minutes and the
 * minutes→millis multiplications use [Math.multiplyExact]/[Math.addExact] so a pathologically
 * large hours value overflows into `null` (invalid) instead of silently wrapping.
 */
private fun parseDuration(hoursText: String, minutesText: String): Long? {
    val hours = if (hoursText.isEmpty()) 0L else hoursText.toLongOrNull() ?: return null
    val minutes = if (minutesText.isEmpty()) 0L else minutesText.toLongOrNull() ?: return null
    if (hours < 0 || minutes < 0) return null

    val totalMinutes = try {
        Math.addExact(Math.multiplyExact(hours, 60L), minutes)
    } catch (overflow: ArithmeticException) {
        return null
    }
    if (totalMinutes <= 0) return null

    return try {
        Math.multiplyExact(totalMinutes, 60_000L)
    } catch (overflow: ArithmeticException) {
        null
    }
}
