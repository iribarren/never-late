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
 * non-blank, and at least one of a valid [durationMinutesText] or [deadlineText] must be present.
 *
 * This function is pure (no ViewModel, no repository, no Android framework calls beyond
 * [SimpleDateFormat][java.text.SimpleDateFormat] parsing, which runs fine on a plain JVM test) so
 * it is the natural place to unit-test every combination of the form's validation rules.
 */
fun validateTaskForm(title: String, durationMinutesText: String, deadlineText: String): TaskFormResult {
    val trimmedTitle = title.trim()
    if (trimmedTitle.isBlank()) return TaskFormResult.Invalid(TaskValidationError.BLANK_TITLE)

    val durationText = durationMinutesText.trim()
    val durationMillis: Long? = if (durationText.isEmpty()) {
        null
    } else {
        parseDurationMinutes(durationText) ?: return TaskFormResult.Invalid(TaskValidationError.INVALID_DURATION)
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
 * Parses a whole number of minutes into milliseconds, or null if [text] is not a positive whole
 * number. Kept separate from [validateTaskForm] so the "what counts as a valid duration" rule has
 * a single, independently testable home.
 */
private fun parseDurationMinutes(text: String): Long? = text.toLongOrNull()?.takeIf { it > 0 }?.let { it * 60_000L }
