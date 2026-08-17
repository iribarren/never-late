package com.neverlate.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neverlate.di.MotionSettingsEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * Compose-side doorway onto the same "should motion be reduced?" criterion
 * [com.neverlate.ui.tasks.TasksViewModel] reads for the countdown cadence (`reduce-motion` spec,
 * D3) — it does **not** perform an independent `Settings.Global` read; both doorways resolve to
 * the single [com.neverlate.data.settings.MotionSettings] binding.
 *
 * Not consumed by any screen yet: today's actual gap is entirely on the ViewModel side (the
 * countdown cadence). This exists so a future Compose-level reduced-motion decision — e.g. an
 * animation this app adds later that needs to check the flag directly rather than relying on
 * `MotionDurationScale` — has one obvious, already-wired place to read it from, instead of a new
 * `Settings.Global` call being added ad hoc.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val motionSettings = remember(context) {
        EntryPointAccessors.fromApplication(context, MotionSettingsEntryPoint::class.java).motionSettings()
    }
    val reduceMotion by motionSettings.reduceMotion.collectAsStateWithLifecycle(initialValue = false)
    return reduceMotion
}
