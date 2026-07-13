package com.neverlate.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.neverlate.ui.theme.NeverLateTheme

/**
 * The maximum width a single-pane screen's content is allowed to grow to on medium/expanded window
 * widths (feature 18b) — chosen as a comfortable reading/scanning width for a list of short rows
 * (task cards, settings sections), not tied to any specific device class.
 */
private val MaxReadableWidth = 640.dp

/**
 * Feature 18b: constrains [content] to [MaxReadableWidth], centered in the available space, on
 * medium/expanded window widths — a compact phone renders [content] edge-to-edge, exactly as
 * before this feature. Used by `AppNavHost` for the two single-pane large-width screens this
 * feature does NOT turn into a list-detail split, Tasks and Settings (Articles gets the two-pane
 * treatment instead — see [com.neverlate.ui.articles.ArticlesListDetailPane]): without this, their
 * one column would stretch edge-to-edge on a tablet, illegible at a glance (see the adaptive-layouts
 * spec's Visual & UX Design section).
 *
 * [widthSizeClass] is looked up as a plain equality check against [WindowWidthSizeClass.Compact]
 * rather than the finer three-way distinction `AppNavHost`'s bar/rail branch uses, because this
 * container has only two behaviours (constrained or not) — [WindowWidthSizeClass.Medium] and
 * [WindowWidthSizeClass.Expanded] are treated identically here (both constrained), matching the
 * spec's per-size-class table for Tasks/Settings.
 */
@Composable
fun ReadableWidthContainer(
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (widthSizeClass == WindowWidthSizeClass.Compact) {
        Box(modifier = modifier.fillMaxSize()) {
            content()
        }
    } else {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(modifier = Modifier.widthIn(max = MaxReadableWidth).fillMaxSize()) {
                content()
            }
        }
    }
}

// Previews can't measure a real window, so [widthSizeClass] below is a literal per function rather
// than derived from the preview's own widthDp — see this file's KDoc for why only the
// Compact/non-Compact split matters (Medium and Expanded render identically).

@Preview(name = "Compact — edge to edge", widthDp = 400, heightDp = 300)
@Composable
private fun ReadableWidthContainerCompactPreview() {
    NeverLateTheme {
        ReadableWidthContainer(widthSizeClass = WindowWidthSizeClass.Compact) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text("Sample content", modifier = Modifier.width(200.dp))
            }
        }
    }
}

@Preview(name = "Expanded — constrained", widthDp = 1000, heightDp = 300)
@Composable
private fun ReadableWidthContainerExpandedPreview() {
    NeverLateTheme {
        ReadableWidthContainer(widthSizeClass = WindowWidthSizeClass.Expanded) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text("Sample content", modifier = Modifier.width(200.dp))
            }
        }
    }
}
