package com.neverlate.domain.focus

/**
 * Pure, Android-free rules for **Modo Foco's device shield**
 * (`docs/specs/2026-08-18-focus-mode-shielding.md`) — the three optional measures ("No molestar",
 * "Fijar la pantalla", "Pantalla siempre encendida") a session can apply on top of the núcleo's
 * full-screen ritual. Only the Do-Not-Disturb restoration decision lives here (D5): the other two
 * measures need no decision table at all (D1 — screen pinning is a query, immersive/keep-screen-on
 * are composable-scoped effects with no persisted state).
 */

/**
 * The three device measures, remembered per D11's defaults and offered as independent switches in
 * the entry dialog (D1: never a single "blindaje" master toggle). [keepScreenOn] also drives the
 * immersive system-bars behaviour — the spec treats the two as one option (D1), not two.
 */
data class FocusShieldOptions(
    val keepScreenOn: Boolean = true,
    val doNotDisturb: Boolean = false,
    val screenPinning: Boolean = false,
)

/**
 * What the restoration trigger (D6: the deliberate exit, every cold start, or the 12h backstop
 * worker) should do about the Do-Not-Disturb receipt (D4), decided by [shieldRestoreActionFor].
 */
sealed interface ShieldRestoreAction {
    /** Leave everything as-is: nothing to restore, or a live session that must keep its shield. */
    data object None : ShieldRestoreAction

    /** Set the interruption filter back to [filter], then clear the receipt. */
    data class RestoreFilter(val filter: Int) : ShieldRestoreAction

    /** Forget the receipt without touching the interruption filter — the person's own change (or
     *  the platform's) wins. */
    data object ClearReceiptOnly : ShieldRestoreAction
}

/**
 * D5's six-row state machine — the single source of truth every restoration trigger (D6) consults.
 * No `NotificationManager`, no Android import: this is provably JVM-testable in milliseconds, the
 * same "keep the decision in plain Kotlin" split every other file in `domain/` follows.
 *
 * @param sessionActive whether a Modo Foco session is currently active (D7 of the núcleo spec) —
 *   a running session always keeps its shield (row 1); this function is never the thing that ends
 *   a session, only ever consulted once it already has (or never started).
 * @param priorFilter the write-ahead receipt (D4) — `null` when absent, meaning nothing was ever
 *   applied that needs undoing.
 * @param currentFilter the interruption filter the system reports right now, or
 *   `NotificationManager.INTERRUPTION_FILTER_UNKNOWN` when the platform could not tell us.
 * @param appliedFilter what the shield sets when it applies (`INTERRUPTION_FILTER_PRIORITY`,
 *   D2) — passed in rather than hardcoded so this function stays Android-import-free.
 * @param policyAccessGranted whether `ACCESS_NOTIFICATION_POLICY` special access is currently
 *   granted — a revoked grant means we cannot act (row 5), never a crash.
 */
fun shieldRestoreActionFor(
    sessionActive: Boolean,
    priorFilter: Int?,
    currentFilter: Int,
    appliedFilter: Int,
    policyAccessGranted: Boolean,
): ShieldRestoreAction {
    // Row 1 (D5): a running session keeps its shield — restoring here would silently disarm it.
    if (sessionActive) return ShieldRestoreAction.None

    // Row 2: nothing was ever applied — there is no receipt to act on.
    if (priorFilter == null) return ShieldRestoreAction.None

    // Row 5: the special access was revoked mid-session. We cannot act on the filter at all, and
    // must not crash or nag — drop the record (D10).
    if (!policyAccessGranted) return ShieldRestoreAction.ClearReceiptOnly

    // Row 6: the system could not report the current filter. Keep the receipt and try again on
    // the next trigger, rather than clearing a record we may still need.
    if (currentFilter == INTERRUPTION_FILTER_UNKNOWN) return ShieldRestoreAction.None

    // Row 4: the person changed Do Not Disturb themselves during the session — their choice wins,
    // and we forget our own receipt rather than overwrite them (R7).
    if (currentFilter != appliedFilter) return ShieldRestoreAction.ClearReceiptOnly

    // Row 3: the normal case — we set it, we put it back.
    return ShieldRestoreAction.RestoreFilter(priorFilter)
}

/**
 * Mirrors `android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN` (value `0`) without
 * importing the Android class — see [shieldRestoreActionFor]'s KDoc for why this file stays
 * Android-import-free. [com.neverlate.ui.focus.AndroidFocusShieldController] passes the real
 * platform constant in; this copy only needs to agree on the same integer value.
 */
private const val INTERRUPTION_FILTER_UNKNOWN = 0
