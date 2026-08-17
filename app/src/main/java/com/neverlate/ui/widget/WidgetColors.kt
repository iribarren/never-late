package com.neverlate.ui.widget

import androidx.compose.runtime.Composable
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.unit.ColorProvider
import com.neverlate.data.tasks.Priority
import com.neverlate.domain.tasks.ColorRole
import com.neverlate.domain.tasks.UrgencyLevel
import com.neverlate.domain.tasks.priorityColorRole
import com.neverlate.domain.tasks.urgencyColorRole
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
 * The *mapping* (which [ColorRole] a level/priority means) used to be duplicated here as a second
 * hand-written `when`, kept in sync with `colorForUrgency`/`Priority.indicatorColor()` only by a
 * KDoc warning. `docs/specs/2026-08-17-widget-hilt-color-token.md` (D3) replaced that duplication
 * with a single shared mapping — [com.neverlate.domain.tasks.urgencyColorRole] and
 * [com.neverlate.domain.tasks.priorityColorRole] in `domain/tasks/ColorRole.kt` — so the two
 * functions below no longer decide anything: they only **resolve** a role to a color/[ColorProvider]
 * in the Glance world, exactly as `colorForUrgency`/`Priority.indicatorColor()` resolve the same
 * roles in the Compose world. There is no "change both" warning to keep anymore: change the shared
 * mapping once, and both resolvers pick it up.
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
 * Resolves the shared [urgencyColorRole] mapping to a [ColorProvider] in the Glance world:
 * [ColorRole.Error] reuses [GlanceTheme.colors]' error role (same "look at this now" signal as the
 * app), [ColorRole.Calm]/[ColorRole.Soon] use the hand-written day/night pairs above (D4 — Glance
 * has no `CompositionLocal` for those roles). No `when` over [UrgencyLevel] here — that decision
 * lives once in [urgencyColorRole].
 */
@Composable
fun urgencyColorProvider(level: UrgencyLevel): ColorProvider = when (urgencyColorRole(level)) {
    ColorRole.Calm -> CalmColor
    ColorRole.Soon -> SoonColor
    ColorRole.Error -> GlanceTheme.colors.error
    ColorRole.Primary, ColorRole.Secondary, ColorRole.Tertiary ->
        error("urgencyColorRole never returns a priority role")
}

/**
 * Resolves the shared [priorityColorRole] mapping to a [ColorProvider] against [GlanceTheme.colors]
 * instead of `MaterialTheme.colorScheme`. `null` role ([Priority.NONE]) stays `null`, same rule as
 * the task card: no visual noise for the default. No `when` over [Priority] here — that decision
 * lives once in [priorityColorRole].
 */
@Composable
fun Priority.glanceIndicatorColor(): ColorProvider? = when (priorityColorRole(this)) {
    null -> null
    ColorRole.Secondary -> GlanceTheme.colors.secondary
    ColorRole.Tertiary -> GlanceTheme.colors.tertiary
    ColorRole.Primary -> GlanceTheme.colors.primary
    ColorRole.Calm, ColorRole.Soon, ColorRole.Error ->
        error("priorityColorRole never returns an urgency role")
}
