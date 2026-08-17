package com.neverlate.domain.tasks

import com.neverlate.data.tasks.Task
import com.neverlate.ui.tasks.TaskUiModel

/**
 * Pure, Android-free session logic for **Modo Foco** (`docs/specs/2026-08-18-focus-mode.md`) — the
 * same "keep the decision in plain Kotlin, let the platform layer stay a thin shell around it"
 * split every other file in this package already uses (see [TaskListShaping.kt]'s KDoc, which this
 * file reuses [sortedBy] from rather than re-implementing a display order).
 *
 * **D1 — the roster is frozen, not live.** A session captures the *set of task ids* pending at the
 * instant it starts ([focusRosterFor]) and keeps exactly that set for its whole life. Everything
 * below reads that frozen [FocusSession.roster] against the *live* [Task]/[TaskUiModel] rows —
 * membership never changes, but a roster member's own completed/deleted state is always read fresh
 * (see [focusProgressFor]'s KDoc for what "deleted" means here).
 */
data class FocusSession(
    /** Epoch millis the session started — the anchor [isFocusSessionActive] measures 12h from. */
    val startedAt: Long,
    /**
     * The 4-digit exit code chosen at entry, in **plaintext** (D2 of the feature spec — this is
     * friction the person can be shown back, not a credential; never hash this, never move it to
     * `EncryptedTokenStorage`). An empty string means "no code was ever recorded" — [FocusSession]
     * itself is agnostic to why (see [com.neverlate.data.UserPreferencesRepository]'s D6 fail-open
     * reads); a blank [exitCode] simply satisfies the code step wherever it is checked.
     */
    val exitCode: String,
    /** The frozen set of [Task.id]s pending when the session started (D1). */
    val roster: Set<Long>,
)

/**
 * How far along a [FocusSession] is: [total] roster members, [done] of them satisfied (completed
 * or vanished — see [focusProgressFor]). [isComplete] is what both the "nothing pending left"
 * screen state and the exit ritual's task-gate read.
 */
data class FocusProgress(val total: Int, val done: Int) {
    val isComplete: Boolean get() = done >= total
}

/** D7: a session older than this is treated as ended — see [isFocusSessionActive]. */
private const val FOCUS_SESSION_MAX_AGE_MILLIS = 12 * 60 * 60 * 1000L

/**
 * D1: the ids of every currently-pending (`completedAt == null`) task — the frozen roster a new
 * session captures at the moment it starts. An already-completed task is never a candidate for the
 * roster in the first place (AC-1); an empty input yields an empty roster, which is a perfectly
 * legal session (D1's table — "The roster is empty at session start").
 */
fun focusRosterFor(tasks: List<Task>): Set<Long> =
    tasks.filter { it.completedAt == null }.map { it.id }.toSet()

/**
 * The rows the Focus screen renders: only [roster] members (AC-4 — a task outside the frozen
 * roster is never shown, pending or not, even if it exists in [uiTasks]), ordered exactly like the
 * Tasks screen's own soonest-first arrangement — [sortedBy] with [TaskSortField.Deadline] /
 * [SortDirection.Ascending], which already sinks a completed task last (D10 — Modo Foco does not
 * honour the persisted sort/group arrangement; there is exactly one correct order here).
 */
fun focusRowsFor(uiTasks: List<TaskUiModel>, roster: Set<Long>): List<TaskUiModel> =
    uiTasks.filter { it.task.id in roster }.sortedBy(TaskSortField.Deadline, SortDirection.Ascending)

/**
 * D1/AC-3: counts a roster id as **done** when its task is completed, **or** when it no longer
 * resolves at all in [uiTasks] — deleted locally, or purged elsewhere and pulled by sync. Both
 * cases fail toward "satisfied", never toward "eternal blocker" (R5 of the feature spec): a roster
 * id this function has never heard of again is exactly as done as one explicitly checked off. Only
 * [roster] is iterated — a pending task that was never on the roster (AC-4) never enters this count
 * either way.
 */
fun focusProgressFor(uiTasks: List<TaskUiModel>, roster: Set<Long>): FocusProgress {
    val completedIds = uiTasks.filter { it.task.completedAt != null }.map { it.task.id }.toSet()
    val presentIds = uiTasks.map { it.task.id }.toSet()
    val done = roster.count { id -> id in completedIds || id !in presentIds }
    return FocusProgress(total = roster.size, done = done)
}

/**
 * D7: `true` while [session] is non-null and less than 12 hours old, evaluated purely against
 * [now] — no alarm, no receiver, no `WorkManager` job (see the feature spec's D7 for why a pure
 * predicate is the right shape here). `false` for a `null` [session] (no session at all) and for one
 * whose [FocusSession.startedAt] is 12 hours or more in the past (a stale session a person has
 * likely forgotten agreeing to — see D7's "why an expiry at all").
 */
fun isFocusSessionActive(session: FocusSession?, now: Long): Boolean {
    if (session == null) return false
    return now - session.startedAt < FOCUS_SESSION_MAX_AGE_MILLIS
}
