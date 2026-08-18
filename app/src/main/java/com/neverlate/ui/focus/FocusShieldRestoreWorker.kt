package com.neverlate.ui.focus

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.neverlate.data.DataStoreUserPreferencesRepository
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.domain.tasks.FOCUS_SESSION_MAX_AGE_MILLIS
import com.neverlate.domain.tasks.isFocusSessionActive
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Modo Foco blindaje's 12h backstop (`docs/specs/2026-08-18-focus-mode-shielding.md`, D6): the
 * one restore trigger that needs no user action at all — "the person never opens the app again"
 * is the single case the deliberate exit and the cold-start hook cannot cover (**R1**, the
 * defining risk this whole feature is organised around).
 *
 * Deferrable `WorkManager` work was chosen over a fourth `AlarmManager` alarm on purpose (D6): it
 * persists its own queue and re-enqueues after `BOOT_COMPLETED` for free, needs no
 * `SCHEDULE_EXACT_ALARM` involvement, and needs no new slot in `requestCodeFor(taskId, kind)`'s
 * per-task numbering — "sometime in the next few hours after the 12h mark" is a perfectly good
 * time to un-silence a phone whose owner stopped using the app half a day ago.
 *
 * Like [com.neverlate.ui.notification.BootRescheduleWorker], this worker has no Activity-scoped
 * dependencies to reuse, so it constructs equivalent ones directly from `applicationContext` — no
 * `@HiltWorker`, same shape as that precedent.
 *
 * **Deliberately not hosted on [com.neverlate.ui.notification.BootRescheduleWorker]** (D6): that
 * worker returns early when `remindersEnabled` is off, which has nothing to do with whether a
 * phone is stuck in Do Not Disturb — overloading it would couple two unrelated invariants inside
 * one early return. Only the cold-start hook it shares (`NeverLateApplication.onCreate`) is
 * reused, not the worker itself.
 */
class FocusShieldRestoreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val userPreferencesRepository = DataStoreUserPreferencesRepository(applicationContext)
        val controller = AndroidFocusShieldController(applicationContext, userPreferencesRepository)
        runFocusShieldRestoreCheck(controller, userPreferencesRepository)
        return Result.success()
    }

    companion object {
        /** Unique work name (D6): a second session must never stack a second backstop. */
        private const val UNIQUE_WORK_NAME = "focus_shield_restore_backstop"

        /**
         * Builds the backstop's [OneTimeWorkRequest] — its own function, separate from [enqueue],
         * so a JVM test can inspect `workSpec.initialDelay` directly (AC-18) without a real
         * `WorkManager`: constructing a [OneTimeWorkRequest] via its builder touches no Android
         * system service, only plain data classes.
         */
        internal fun buildBackstopRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<FocusShieldRestoreWorker>()
                .setInitialDelay(FOCUS_SESSION_MAX_AGE_MILLIS, TimeUnit.MILLISECONDS)
                .build()

        /**
         * Enqueues the backstop at session start, replacing any previous one (D6/AC-17) — the
         * initial delay is [FOCUS_SESSION_MAX_AGE_MILLIS], the **same** constant the session's own
         * expiry predicate uses (AC-18), so the two can never drift apart.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, buildBackstopRequest())
        }

        /** Cancelled on the deliberate exit (D6) — a completed session needs no backstop. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        /**
         * AC-16: every cold start (`NeverLateApplication.onCreate`, next to the existing
         * [com.neverlate.ui.notification.BootRescheduleWorker.enqueue] call) also enqueues an
         * immediate, **non-unique** run of this worker — covers "killed from recents / by memory
         * pressure, then reopened" without waiting for the 12h backstop. Deliberately **not**
         * [enqueue] (which would `REPLACE` a live session's still-pending 12h backstop with an
         * immediate one, cutting its delay short): this is a second, independent work item, so an
         * active session's own backstop is left untouched.
         */
        fun enqueueColdStartCheck(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<FocusShieldRestoreWorker>().build())
        }
    }
}

/**
 * AC-19: the exact restore decision every trigger runs — [doWork] delegates to this, and so does
 * the JVM test suite (against a [FakeFocusShieldController][com.neverlate.ui.focus.FakeFocusShieldController]
 * in `test/`), so "the worker behaves identically to the other two triggers" is provable rather
 * than merely asserted. `internal` (not `private`) precisely so that test can call it directly.
 */
internal suspend fun runFocusShieldRestoreCheck(
    controller: FocusShieldController,
    userPreferencesRepository: UserPreferencesRepository,
) {
    val session = userPreferencesRepository.userPreferences.first().focusSession
    val sessionActive = isFocusSessionActive(session, now = System.currentTimeMillis())
    controller.restore(sessionActive = sessionActive)
}
