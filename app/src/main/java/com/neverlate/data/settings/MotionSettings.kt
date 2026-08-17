package com.neverlate.data.settings

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The single "should motion be reduced?" criterion for the whole app (`reduce-motion` spec, D3).
 *
 * Declared as an interface — same rationale as
 * [com.neverlate.data.UserPreferencesRepository] — so [com.neverlate.ui.tasks.TasksViewModel] can
 * be unit-tested against a simple in-memory fake, with no Android runtime involved, even though
 * (unlike that repository) this one persists nothing of its own: it only *reads* a system setting.
 *
 * Per the spec's D3, exactly one file in the app is allowed to touch
 * [Settings.Global.ANIMATOR_DURATION_SCALE]: this one. Every other consumer — the ViewModel or the
 * Compose-side [com.neverlate.ui.theme.rememberReduceMotion] — goes through this interface instead
 * of reading the setting itself.
 */
interface MotionSettings {
    /**
     * Emits `true` whenever the platform's *Quitar animaciones* (Remove animations) setting is on,
     * `false` otherwise, starting with the current value and following it live.
     */
    val reduceMotion: Flow<Boolean>
}

/**
 * Real implementation, backed directly by [Settings.Global].
 *
 * Deliberately **not** [androidx.compose.ui.platform.LocalAccessibilityManager]: Compose's
 * `AccessibilityManager` interface only exposes `calculateRecommendedTimeoutMillis` — it has no
 * animation-scale accessor at all (spec D3). The real signal is
 * `Settings.Global.ANIMATOR_DURATION_SCALE`, the exact key `WindowRecomposer` itself reads to
 * install `MotionDurationScale` into the composition's coroutine context.
 */
class SystemMotionSettings(private val context: Context) : MotionSettings {

    override val reduceMotion: Flow<Boolean> = callbackFlow {
        // Emit the current value immediately on collection, so a caller that starts observing
        // after the setting was already changed still sees the correct state right away, rather
        // than waiting for the next system-level write.
        trySend(currentlyReduced())

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(currentlyReduced())
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            /* notifyForDescendants = */ false,
            observer,
        )

        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()

    private fun currentlyReduced(): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
