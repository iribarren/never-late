package com.neverlate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.neverlate.domain.tasks.ColorRole
import com.neverlate.domain.tasks.UrgencyLevel
import com.neverlate.domain.tasks.urgencyColorRole

/**
 * D10 (Modo Foco, `docs/specs/2026-08-18-focus-mode.md`): promoted out of `ui/tasks/TasksScreen.kt`
 * (where it used to be `private`) so the Focus screen can be a second **consumer** of the exact same
 * mapping instead of a second copy — the same "one mapping, thin per-world resolvers" shape the
 * widget refactor established for [urgencyColorRole] itself (see that function's KDoc). This move is
 * behaviour-preserving: the body is unchanged, only its visibility and home package moved.
 *
 * Resolves the shared [urgencyColorRole] mapping (`domain/tasks/ColorRole.kt`, feature
 * "widget-hilt-color-token") to a themed [Color]: [ColorRole.Error] reuses
 * [MaterialTheme.colorScheme]'s existing `error` role — visually "urgent" and "error" are the same
 * signal, "look at this now" — while [ColorRole.Calm]/[ColorRole.Soon] read from [NeverLateExtras],
 * the small extra color set feature 17 adds alongside `MaterialTheme.colorScheme` for the roles
 * Material 3 itself doesn't define. This function no longer decides *which* role a level means — only
 * how to paint that role — so it stays in sync with the widget's `urgencyColorProvider`
 * (`ui/widget/WidgetColors.kt`) by construction.
 */
@Composable
fun colorForUrgency(level: UrgencyLevel): Color = when (urgencyColorRole(level)) {
    ColorRole.Calm -> NeverLateExtras.colors.calm
    ColorRole.Soon -> NeverLateExtras.colors.soon
    ColorRole.Error -> MaterialTheme.colorScheme.error
    ColorRole.Primary, ColorRole.Secondary, ColorRole.Tertiary ->
        error("urgencyColorRole never returns a priority role")
}
