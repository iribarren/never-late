package com.neverlate.ui.widget

import androidx.compose.runtime.Composable
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.unit.ColorProvider
import com.neverlate.data.tasks.Priority
import com.neverlate.domain.tasks.UrgencyLevel
import com.neverlate.ui.theme.outlineVariantDark
import com.neverlate.ui.theme.outlineVariantLight
import com.neverlate.ui.theme.urgencyCalmDark
import com.neverlate.ui.theme.urgencyCalmLight
import com.neverlate.ui.theme.urgencySoonDark
import com.neverlate.ui.theme.urgencySoonLight

/**
 * The widget's small "color adapter" layer (feature 05b) — twins, for the Glance world, of
 * `colorForUrgency` (`ui/tasks/TasksScreen.kt`) and `Priority.indicatorColor()`
 * (`ui/tasks/PriorityUi.kt`). The widget **cannot** call either directly: both are `@Composable`
 * functions reading `MaterialTheme.colorScheme`/`NeverLateExtras.colors` off a
 * [androidx.compose.runtime.CompositionLocal] that only exists inside a
 * [androidx.compose.material3.MaterialTheme] composition — the widget's composition is a
 * [androidx.glance.GlanceTheme] one instead, a completely different `CompositionLocal` tree
 * Glance's `RemoteViews` translator understands. Same color, two worlds — see the feature spec
 * (D1) and `tutorial/05b-*.md` for the full explanation.
 *
 * Because these two functions duplicate a *mapping* (role choice per level/priority) rather than a
 * *value* (the color itself, which still comes from [com.neverlate.ui.theme.Color] / [GlanceTheme]),
 * **whoever changes `colorForUrgency` or `Priority.indicatorColor()` must change the matching
 * function here too**, or the task card and the widget will silently disagree on what a color
 * means.
 */

// Calm/Soon have no Material 3 role (see NeverLateExtendedColors in Theme.kt), so they cannot come
// from GlanceTheme.colors — they are declared here, by hand, from the exact same Color.kt values
// the app's own extended colors use, via the day/night ColorProvider Glance provides for roles
// with no CompositionLocal of their own.
private val CalmColor: ColorProvider = DayNightColorProvider(day = urgencyCalmLight, night = urgencyCalmDark)
private val SoonColor: ColorProvider = DayNightColorProvider(day = urgencySoonLight, night = urgencySoonDark)

/**
 * [GlanceTheme.colors] (`androidx.glance.color.ColorProviders`) exposes only `outline`, not
 * `outlineVariant` — Material 3's [androidx.compose.material3.ColorScheme] has both roles, but
 * Glance's bridge only carries one of them across. The row dividers need the subtler
 * `outlineVariant` role the task list itself would use for a hairline, so this reads it the same
 * way Calm/Soon are read above: a hand-written day/night pair from the exact values `ui/theme/Color.kt`
 * already defines for that role, not a new color.
 */
val dividerColor: ColorProvider = DayNightColorProvider(day = outlineVariantLight, night = outlineVariantDark)

/**
 * Mirrors `colorForUrgency` in `ui/tasks/TasksScreen.kt`: [UrgencyLevel.Urgent]/[UrgencyLevel.Overdue]
 * reuse the error role (same "look at this now" signal as the app), [UrgencyLevel.Calm]/[UrgencyLevel.Soon]
 * use the hand-written pair above.
 */
@Composable
fun urgencyColorProvider(level: UrgencyLevel): ColorProvider = when (level) {
    UrgencyLevel.Calm -> CalmColor
    UrgencyLevel.Soon -> SoonColor
    UrgencyLevel.Urgent, UrgencyLevel.Overdue -> GlanceTheme.colors.error
}

/**
 * Mirrors `Priority.indicatorColor()` in `ui/tasks/PriorityUi.kt` against [GlanceTheme.colors]
 * instead of `MaterialTheme.colorScheme`. [Priority.NONE] returns `null`, same rule as the task
 * card: no visual noise for the default.
 */
@Composable
fun Priority.glanceIndicatorColor(): ColorProvider? = when (this) {
    Priority.NONE -> null
    Priority.LOW -> GlanceTheme.colors.secondary
    Priority.MEDIUM -> GlanceTheme.colors.tertiary
    Priority.HIGH -> GlanceTheme.colors.primary
}
