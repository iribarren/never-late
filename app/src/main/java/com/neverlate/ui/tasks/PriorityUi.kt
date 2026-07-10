package com.neverlate.ui.tasks

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.neverlate.R
import com.neverlate.data.tasks.Priority

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
 * The task-list indicator color for a non-[Priority.NONE] priority, picked from **existing** theme
 * roles only (no one-off hex) so it re-themes with the rest of the app and works in light/dark. The
 * scale rises in salience — brand [primary] for [Priority.HIGH] down to the muted [secondary] for
 * [Priority.LOW]. [Priority.NONE] has no indicator, so it returns `null` and the caller draws
 * nothing (US-2: no visual noise for the default).
 */
@Composable
fun Priority.indicatorColor(): Color? = when (this) {
    Priority.NONE -> null
    Priority.LOW -> MaterialTheme.colorScheme.secondary
    Priority.MEDIUM -> MaterialTheme.colorScheme.tertiary
    Priority.HIGH -> MaterialTheme.colorScheme.primary
}
