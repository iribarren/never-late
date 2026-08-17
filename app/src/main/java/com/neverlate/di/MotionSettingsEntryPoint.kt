package com.neverlate.di

import com.neverlate.data.settings.MotionSettings
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Lets [com.neverlate.ui.theme.rememberReduceMotion] reach the Hilt-bound [MotionSettings] from
 * inside a plain `@Composable`, which has no constructor for Hilt's `@Inject` to hook into (the
 * usual doorway is a `@HiltViewModel`, e.g. [com.neverlate.ui.tasks.TasksViewModel]'s own
 * `MotionSettings` injection — but a theme-level accessor is not tied to any one screen's
 * ViewModel). Resolved via [dagger.hilt.android.EntryPointAccessors.fromApplication], the same
 * escape hatch [WidgetEntryPoint] uses for [com.neverlate.ui.widget.PendingTasksWidget] — see that
 * file's KDoc for the fuller rationale of why an `@EntryPoint` is the right shape here.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MotionSettingsEntryPoint {
    fun motionSettings(): MotionSettings
}
