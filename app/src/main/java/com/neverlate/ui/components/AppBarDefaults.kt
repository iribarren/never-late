package com.neverlate.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable

/**
 * Feature 20's "don't hand-paint, pass a colors object" idiom, extracted **once** so every screen's
 * `TopAppBar` reuses the exact same brand treatment instead of repeating the same four color
 * arguments at each call site (extend, don't duplicate — the same rule [MessageState] and
 * `SettingsSectionCard` already follow for their own repeated shapes).
 *
 * The **container** color (`primary`, fully saturated — the confirmed, mockup-faithful choice) is
 * always paired with its **on-container** counterpart (`onPrimary`) for the title, the navigation
 * icon (the back arrow), and any action icons, so text/icons on the filled bar stay legible. Because
 * every value here is a [MaterialTheme.colorScheme] *role* rather than a fixed color, this is
 * automatically correct in light, dark, and Material You (dynamicColor) — no per-theme branch is
 * ever written.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun brandedTopAppBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.primary,
    // Kept identical to containerColor: a scrolling screen's bar should stay the same brand fill
    // once content scrolls underneath it, rather than fading to the default surface color.
    scrolledContainerColor = MaterialTheme.colorScheme.primary,
    titleContentColor = MaterialTheme.colorScheme.onPrimary,
    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
)
