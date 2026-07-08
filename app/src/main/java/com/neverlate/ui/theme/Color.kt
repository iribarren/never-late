package com.neverlate.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand color roles (feature 16), replacing the Android Studio template's purple placeholders.
 *
 * Material 3 does not assign colors to individual widgets; it assigns them to named *roles*
 * (`primary`, `onPrimary`, `primaryContainer`, `surface`, `outline`, ...) and every widget reads
 * its color from a role rather than a hardcoded hex value (see [MaterialTheme.colorScheme] usage
 * across `ui/`). That indirection is what lets the whole app re-theme itself — light/dark, or
 * brand/Material You — by swapping which [androidx.compose.material3.ColorScheme] is active,
 * instead of editing every screen.
 *
 * These values were generated from the brand seed `#3B5BDB` using Material 3's HCT (Hue, Chroma,
 * Tone) algorithm — the same math Material Theme Builder runs, applied here directly since that
 * external tool isn't available in this environment. HCT builds a *tonal palette* per hue: a
 * scale of colors that share the same perceived hue/chroma but vary only in tone (0 = black,
 * 100 = white), so "primary at tone 40" and "primary at tone 90" always look like the same color
 * at different lightness, in both light and dark mode. Five tonal palettes are derived from the
 * seed (see [Theme.kt] for exactly how): primary (the seed's own hue/chroma), secondary and
 * tertiary (same/shifted hue, lower chroma, for supporting accents), and two near-gray "neutral"
 * palettes for surfaces/outlines. Each role below is one fixed tone picked from one of those
 * palettes — that fixed tone->role assignment (`primary` = tone 40 in light / tone 80 in dark,
 * etc.) is itself part of the Material 3 spec, applied identically in [Theme.kt].
 *
 * Naming follows Material Theme Builder's own Compose export convention: `<role><Scheme>`, e.g.
 * [primaryLight]/[primaryDark].
 */

// --- Primary (brand seed #3B5BDB tonal palette) ---
val primaryLight = Color(0xFF3052D2)
val onPrimaryLight = Color(0xFFFFFFFF)
val primaryContainerLight = Color(0xFFDDE1FF)
val onPrimaryContainerLight = Color(0xFF001355)

val primaryDark = Color(0xFFB8C3FF)
val onPrimaryDark = Color(0xFF002388)
val primaryContainerDark = Color(0xFF0736BA)
val onPrimaryContainerDark = Color(0xFFDDE1FF)

// --- Secondary (same hue as primary, low chroma) ---
val secondaryLight = Color(0xFF5A5D72)
val onSecondaryLight = Color(0xFFFFFFFF)
val secondaryContainerLight = Color(0xFFDFE1F9)
val onSecondaryContainerLight = Color(0xFF171B2C)

val secondaryDark = Color(0xFFC3C5DD)
val onSecondaryDark = Color(0xFF2C2F42)
val secondaryContainerDark = Color(0xFF424659)
val onSecondaryContainerDark = Color(0xFFDFE1F9)

// --- Tertiary (hue shifted +60 degrees from primary, moderate chroma) ---
val tertiaryLight = Color(0xFF76546E)
val onTertiaryLight = Color(0xFFFFFFFF)
val tertiaryContainerLight = Color(0xFFFFD7F3)
val onTertiaryContainerLight = Color(0xFF2C1229)

val tertiaryDark = Color(0xFFE4BAD9)
val onTertiaryDark = Color(0xFF44273E)
val tertiaryContainerDark = Color(0xFF5C3D56)
val onTertiaryContainerDark = Color(0xFFFFD7F3)

// --- Error (Material 3's standard fixed error hue/chroma, not derived from the brand seed) ---
val errorLight = Color(0xFFBA1A1A)
val onErrorLight = Color(0xFFFFFFFF)
val errorContainerLight = Color(0xFFFFDAD6)
val onErrorContainerLight = Color(0xFF410002)

val errorDark = Color(0xFFFFB4AB)
val onErrorDark = Color(0xFF690005)
val errorContainerDark = Color(0xFF93000A)
val onErrorContainerDark = Color(0xFFFFDAD6)

// --- Neutral (near-gray, primary hue, very low chroma): background/surface family ---
val backgroundLight = Color(0xFFFBF8FD)
val onBackgroundLight = Color(0xFF1B1B1F)
val surfaceLight = Color(0xFFFBF8FD)
val onSurfaceLight = Color(0xFF1B1B1F)
val surfaceDimLight = Color(0xFFDCD9DE)
val surfaceBrightLight = Color(0xFFFBF8FD)
val surfaceContainerLowestLight = Color(0xFFFFFFFF)
val surfaceContainerLowLight = Color(0xFFF5F2F7)
val surfaceContainerLight = Color(0xFFF0EDF1)
val surfaceContainerHighLight = Color(0xFFEAE7EC)
val surfaceContainerHighestLight = Color(0xFFE4E1E6)
val inverseSurfaceLight = Color(0xFF303034)
val inverseOnSurfaceLight = Color(0xFFF2F0F4)
val scrimLight = Color(0xFF000000)

val backgroundDark = Color(0xFF131316)
val onBackgroundDark = Color(0xFFE4E1E6)
val surfaceDark = Color(0xFF131316)
val onSurfaceDark = Color(0xFFE4E1E6)
val surfaceDimDark = Color(0xFF131316)
val surfaceBrightDark = Color(0xFF39393C)
val surfaceContainerLowestDark = Color(0xFF0E0E11)
val surfaceContainerLowDark = Color(0xFF1B1B1F)
val surfaceContainerDark = Color(0xFF1F1F23)
val surfaceContainerHighDark = Color(0xFF2A2A2D)
val surfaceContainerHighestDark = Color(0xFF353438)
val inverseSurfaceDark = Color(0xFFE4E1E6)
val inverseOnSurfaceDark = Color(0xFF303034)
val scrimDark = Color(0xFF000000)

// --- Neutral variant (near-gray, primary hue, slightly more chroma than neutral): outlines ---
val surfaceVariantLight = Color(0xFFE2E1EC)
val onSurfaceVariantLight = Color(0xFF45464F)
val outlineLight = Color(0xFF767680)
val outlineVariantLight = Color(0xFFC6C5D0)

val surfaceVariantDark = Color(0xFF45464F)
val onSurfaceVariantDark = Color(0xFFC6C5D0)
val outlineDark = Color(0xFF90909A)
val outlineVariantDark = Color(0xFF45464F)

// --- Inverse primary (primary tone picked from the *other* scheme, for content drawn on an
// inverseSurface, e.g. a Snackbar) ---
val inversePrimaryLight = Color(0xFFB8C3FF)
val inversePrimaryDark = Color(0xFF3052D2)
