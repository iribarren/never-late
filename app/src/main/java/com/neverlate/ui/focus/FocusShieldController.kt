package com.neverlate.ui.focus

import android.app.NotificationManager
import android.content.Context
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.domain.focus.ShieldRestoreAction
import com.neverlate.domain.focus.shieldRestoreActionFor
import kotlinx.coroutines.flow.first

/**
 * Modo Foco blindaje's Do-Not-Disturb seam (`docs/specs/2026-08-18-focus-mode-shielding.md`,
 * D1/D2). **Context-scoped only** — screen pinning needs an `Activity` (D7) and lives in
 * `AppNavHost`'s coroutine / `MainActivity`, immersive/keep-screen-on are composable-scoped
 * effects in [FocusScreen] (D8); neither belongs on this interface, which exists purely for the
 * one measure that outlives the window and needs undo machinery (D1).
 *
 * Declared as an interface — rather than exposing [AndroidFocusShieldController] directly — so
 * [applyFocusShieldOnSessionStart] and [FocusViewModel]/[FocusShieldRestoreWorker] can be unit
 * tested against an in-memory fake, the same "interface + real impl + fake for tests" shape every
 * other seam in this app uses ([com.neverlate.data.tasks.TaskRepository],
 * [com.neverlate.data.settings.MotionSettings], ...).
 */
interface FocusShieldController {

    /**
     * The raw effect only (D2): sets the interruption filter to
     * `NotificationManager.INTERRUPTION_FILTER_PRIORITY`. Callers are responsible for the
     * write-ahead receipt (D4) — see [applyFocusShieldOnSessionStart] — this method never touches
     * [UserPreferencesRepository] itself, so its own ordering is impossible to get backwards.
     *
     * Returns `false` (never throws) when the effect could not be applied at all — no
     * `NotificationManager`, or a `SecurityException` from an OEM that disagrees (D10) — so the
     * caller knows there is nothing to restore later.
     */
    suspend fun applyDoNotDisturb(): Boolean

    /**
     * D5/D6: the one restoration decision, run identically by all three triggers (the deliberate
     * exit, every cold start, and the 12h backstop worker). Reads the receipt from
     * [UserPreferencesRepository], asks [com.neverlate.domain.focus.shieldRestoreActionFor], and
     * acts on the result — restoring the filter and/or clearing the receipt, never both silently
     * skipped nor both silently done. Idempotent: running it twice in a row with nothing changed
     * in between is a no-op the second time (AC-20 — the second call sees `priorFilter == null`
     * and returns via row 2).
     */
    suspend fun restore(sessionActive: Boolean)

    /** Whether `ACCESS_NOTIFICATION_POLICY` special access is currently granted. */
    fun isPolicyAccessGranted(): Boolean

    /** The interruption filter as the system reports it right now, or
     *  `NotificationManager.INTERRUPTION_FILTER_UNKNOWN` if it cannot be read (no
     *  `NotificationManager`, D10's null-guard). */
    fun currentInterruptionFilter(): Int

    /**
     * D6: cancels [FocusShieldRestoreWorker]'s 12h backstop — called from the deliberate exit
     * only (`FocusViewModel`'s two exit paths, AC-15), never from the worker's own run or the
     * cold-start trigger, both of which must be free to let a still-pending backstop keep waiting
     * for a session that has not actually ended.
     */
    fun cancelBackstop()
}

/** Real implementation, over the platform `NotificationManager` and the shared
 *  [UserPreferencesRepository] (D4's receipt lives there, never a second store). */
class AndroidFocusShieldController(
    private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
) : FocusShieldController {

    // D10: null-guarded exactly like ExactAlarmPermissionNotice's alarmManager == null check —
    // absent in test/edge contexts, never crashes the caller.
    private val notificationManager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    override suspend fun applyDoNotDisturb(): Boolean {
        val manager = notificationManager ?: return false
        return try {
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            true
        } catch (_: SecurityException) {
            // D10: an OEM that refuses this call — the session continues regardless.
            false
        }
    }

    override fun isPolicyAccessGranted(): Boolean =
        notificationManager?.isNotificationPolicyAccessGranted ?: false

    override fun currentInterruptionFilter(): Int =
        notificationManager?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN

    override suspend fun restore(sessionActive: Boolean) {
        val preferences = userPreferencesRepository.userPreferences.first()
        val action = shieldRestoreActionFor(
            sessionActive = sessionActive,
            priorFilter = preferences.focusShieldPriorFilter,
            currentFilter = currentInterruptionFilter(),
            appliedFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            policyAccessGranted = isPolicyAccessGranted(),
        )
        when (action) {
            ShieldRestoreAction.None -> Unit

            is ShieldRestoreAction.RestoreFilter -> {
                try {
                    notificationManager?.setInterruptionFilter(action.filter)
                } catch (_: SecurityException) {
                    // D10: best-effort — the receipt is cleared below regardless, so a future
                    // trigger never retries a restore the platform has already refused once.
                }
                userPreferencesRepository.saveFocusShieldPriorFilter(null)
            }

            ShieldRestoreAction.ClearReceiptOnly ->
                userPreferencesRepository.saveFocusShieldPriorFilter(null)
        }
    }

    override fun cancelBackstop() {
        try {
            FocusShieldRestoreWorker.cancel(context)
        } catch (_: IllegalStateException) {
            // D10/AC-16's same tolerance: WorkManager not initialized in this process (a plain
            // JVM/Robolectric test context that never touches WorkManager) — nothing to cancel.
        }
    }
}

/**
 * D4's write-ahead start sequence for the Do-Not-Disturb measure — the three bullets under the
 * spec's `if options.doNotDisturb && policyAccessGranted:` block, kept as one plain `suspend`
 * function (not folded into [FocusShieldController.applyDoNotDisturb]) precisely so the
 * **ordering** — receipt written, then the backstop enqueued, then the effect actually applied —
 * is provable against fakes in a JVM test (AC-10/AC-11), not just plausible from reading the code.
 * Screen pinning and immersive/keep-screen-on are **not** part of this sequence (D1) — see
 * `AppNavHost.kt`'s `onFocusClick` for where those two are applied, right alongside this call.
 *
 * A no-op when [doNotDisturbRequested] is `false` or the special access is not granted (D10/D11):
 * no receipt is written, [enqueueBackstop] is never called, and [FocusShieldController.applyDoNotDisturb]
 * is never invoked — exactly AC-11.
 *
 * @param enqueueBackstop schedules [FocusShieldRestoreWorker] (D6) — injected as a lambda rather
 *   than called directly so this function stays testable against a fake with no real `WorkManager`.
 */
suspend fun applyFocusShieldOnSessionStart(
    controller: FocusShieldController,
    userPreferencesRepository: UserPreferencesRepository,
    enqueueBackstop: () -> Unit,
    doNotDisturbRequested: Boolean,
) {
    if (!doNotDisturbRequested || !controller.isPolicyAccessGranted()) return

    // ① RECEIPT FIRST — write-ahead, before anything that could silence the phone.
    val currentFilter = controller.currentInterruptionFilter()
    userPreferencesRepository.saveFocusShieldPriorFilter(currentFilter)

    // ② the backstop, before the effect — so even a process death right after applying DND still
    // has a trigger that will eventually undo it (D6).
    enqueueBackstop()

    // ③ the effect itself, last.
    val applied = controller.applyDoNotDisturb()
    if (!applied) {
        // D10: the effect never actually took (no NotificationManager, or a refusing OEM) —
        // nothing to restore later, so the receipt would only ever be dead weight.
        userPreferencesRepository.saveFocusShieldPriorFilter(null)
    }
}
