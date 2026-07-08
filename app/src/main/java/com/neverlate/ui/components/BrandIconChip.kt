package com.neverlate.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.neverlate.ui.theme.NeverLateTheme

/**
 * A reusable, **stateless** leading-icon chip (feature 20): a small rounded [Surface] that paints
 * [icon] in a brand-container color, echoing the master mockup's `.leading` element. It generalizes
 * the icon+title "chip" language `SettingsSectionCard` (feature 15) introduced for its own header,
 * in the same spirit as [MessageState] (feature 17) — a small piece of repeated UI extracted into
 * `ui/components` instead of hand-painted again at every call site.
 *
 * The container/content pair is [MaterialTheme.colorScheme]'s `secondaryContainer` /
 * `onSecondaryContainer` — a lighter brand tint than the fully-saturated `primary` used on the top
 * app bars (see [brandedTopAppBarColors]), so the two branded surfaces read as distinct rather than
 * merging into one block of color. Because both are *roles*, the pairing stays legible in light,
 * dark, and Material You.
 *
 * [contentDescription] defaults to `null`: this chip is meant to sit next to a text label (a task
 * or article title) that already conveys the row's meaning, so by default the icon is purely
 * decorative and a screen reader should not announce it a second time — the same rule
 * `SettingsSectionCard`'s header icon follows. Pass a real description only for the rare case where
 * the chip is *not* accompanied by an equivalent text label.
 */
@Composable
fun BrandIconChip(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Surface(
        modifier = modifier.size(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BrandIconChipPreview() {
    NeverLateTheme {
        BrandIconChip(icon = Icons.AutoMirrored.Filled.Assignment)
    }
}
