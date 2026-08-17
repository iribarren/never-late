package com.neverlate.ui.tasks

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.neverlate.R
import com.neverlate.data.tasks.Priority
import com.neverlate.domain.tasks.ColorRole
import com.neverlate.domain.tasks.priorityColorRole

/**
 * UI-only mappings for [Priority] (feature 13b), kept out of the data-layer enum so [Priority] stays
 * a plain, JVM/serialization-friendly type with no Android or Compose dependency. Both the edit
 * screen's chip selector and the task list's indicator read from here, so a label or color is
 * defined once.
 */

/** The user-facing label for a priority, from `strings.xml` (localized per feature 08). */
@StringRes
fun Priority.labelRes(): Int = when (this) {
    Priority.NONE -> R.string.priority_none
    Priority.LOW -> R.string.priority_low
    Priority.MEDIUM -> R.string.priority_medium
    Priority.HIGH -> R.string.priority_high
}

/**
 * Resolves the shared [priorityColorRole] mapping (`domain/tasks/ColorRole.kt`, feature
 * "widget-hilt-color-token") to a themed [Color], picked from **existing** theme roles only (no
 * one-off hex) so it re-themes with the rest of the app and works in light/dark. The scale rises in
 * salience — brand `primary` for [Priority.HIGH] down to the muted `secondary` for [Priority.LOW].
 * [Priority.NONE] resolves to no role, so it returns `null` and the caller draws nothing (US-2: no
 * visual noise for the default). This function no longer decides *which* role a priority means —
 * only how to paint that role — so it stays in sync with the widget's
 * `Priority.glanceIndicatorColor()` (`ui/widget/WidgetColors.kt`) by construction.
 */
@Composable
fun Priority.indicatorColor(): Color? = when (priorityColorRole(this)) {
    null -> null
    ColorRole.Secondary -> MaterialTheme.colorScheme.secondary
    ColorRole.Tertiary -> MaterialTheme.colorScheme.tertiary
    ColorRole.Primary -> MaterialTheme.colorScheme.primary
    ColorRole.Calm, ColorRole.Soon, ColorRole.Error ->
        error("priorityColorRole never returns an urgency role")
}
