package com.neverlate

import android.app.Application
import com.neverlate.ui.focus.FocusShieldRestoreWorker
import com.neverlate.ui.notification.BootRescheduleWorker
import dagger.hilt.android.HiltAndroidApp

/**
 * Feature 13d: the app's [Application] subclass, annotated `@HiltAndroidApp`.
 *
 * This annotation is what triggers Hilt's code generation: it builds the root
 * `SingletonComponent` — the top of the dependency graph every `@Module` in `di/` contributes
 * to, and every `@AndroidEntryPoint` class (currently just [MainActivity]) and `@HiltViewModel`
 * (every screen's ViewModel) ultimately gets its dependencies from. Before this feature, that same
 * "one graph for the whole process" role was played by hand: whereas the equivalent manual wiring
 * used to live imperatively in `MainActivity.onCreate` (see that class's KDoc for the "before").
 *
 * Registered in `AndroidManifest.xml` via `android:name=".NeverLateApplication"`, exactly like any
 * other custom `Application` subclass — Hilt needs no other manifest entry.
 *
 * Times-up-alert feature (D10): also enqueues [BootRescheduleWorker] on every cold start, not just
 * after `BOOT_COMPLETED` (see that worker's KDoc for why a reboot alone leaves a real hole). The
 * worker is idempotent, so enqueuing it unconditionally here is safe.
 *
 * Modo Foco blindaje (`docs/specs/2026-08-18-focus-mode-shielding.md`, D6/AC-16): also enqueues an
 * immediate [FocusShieldRestoreWorker] check on every cold start — the second of the three
 * Do-Not-Disturb restore triggers, covering "killed from recents / by memory pressure, then
 * reopened" (the *common* case, not an edge case). The worker itself decides whether there is
 * anything to restore; enqueuing it unconditionally here is as safe as [BootRescheduleWorker]'s
 * own unconditional enqueue above.
 */
@HiltAndroidApp
class NeverLateApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // WorkManager's own ContentProvider auto-initializes it before Application.onCreate() runs
        // on a real device, but plenty of JVM/Robolectric unit tests instantiate this Application
        // (via the manifest's android:name) without that provider ever running, since they only
        // care about Hilt/DataStore/Room, not background work. Rather than make every such test
        // carry WorkManager test-init boilerplate for a call it neither exercises nor asserts on,
        // this best-effort reschedule swallows exactly that one failure mode.
        try {
            BootRescheduleWorker.enqueue(this)
        } catch (_: IllegalStateException) {
            // WorkManager not initialized in this process (see above) — nothing to reschedule yet.
        }
        try {
            FocusShieldRestoreWorker.enqueueColdStartCheck(this)
        } catch (_: IllegalStateException) {
            // WorkManager not initialized in this process (see above) — nothing to restore yet.
        }
    }
}
