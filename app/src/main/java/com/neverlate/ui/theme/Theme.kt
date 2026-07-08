package com.neverlate.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.neverlate.data.ThemeMode

/**
 * Resolves the user's [ThemeMode] preference into the plain light/dark boolean that
 * [NeverLateTheme] already understands.
 *
 * Kept as a small pure function (no Android/Compose types) so it can be unit-tested on the JVM
 * and reused wherever the app needs to turn the preference into a concrete decision. [SYSTEM]
 * defers to [systemInDark], which the caller obtains from `isSystemInDarkTheme()`.
 */
fun themeModeToDark(mode: ThemeMode, systemInDark: Boolean): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> systemInDark
}

// The role -> tone assignment below (e.g. primary = tone 40 in light, tone 80 in dark) is fixed
// by the Material 3 spec, not chosen per app: every M3 app maps its tonal palettes onto roles the
// same way, which is exactly what keeps light/dark contrast (and accessibility) consistent across
// the whole ecosystem. Only the *palettes themselves* (Color.kt) are brand-specific.
private val DarkColorScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    inversePrimary = inversePrimaryDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    surfaceTint = primaryDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceDim = surfaceDimDark,
)

private val LightColorScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    inversePrimary = inversePrimaryLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    surfaceTint = primaryLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceDim = surfaceDimLight,
)

/**
 * Extra semantic colors (feature 17) that sit *alongside* [MaterialTheme.colorScheme] rather than
 * inside it: Material 3's [androidx.compose.material3.ColorScheme] only has slots for the roles
 * the M3 spec itself defines (`primary`, `error`, ...), and there is no built-in "calm" or "soon"
 * role for the task countdown's urgency cue. Adding a second, small
 * [androidx.compose.runtime.CompositionLocal] for exactly the roles Material 3 is missing — the
 * same technique Material Theme Builder's own "extended colors" export uses — keeps every color
 * flowing through the same "role, not hardcoded hex" indirection as [Color.kt]'s KDoc describes,
 * instead of the countdown `Text` reaching for a raw `Color(0xFF...)` literal.
 */
data class NeverLateExtendedColors(val calm: Color, val soon: Color)

private val LightExtendedColors = NeverLateExtendedColors(calm = urgencyCalmLight, soon = urgencySoonLight)
private val DarkExtendedColors = NeverLateExtendedColors(calm = urgencyCalmDark, soon = urgencySoonDark)

// staticCompositionLocalOf (rather than plain compositionLocalOf) is the right choice here: these
// colors only ever change when the whole theme is swapped (light <-> dark), never independently
// mid-composition, so there is no need to pay for compositionLocalOf's more granular
// (per-read) invalidation tracking.
private val LocalNeverLateExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/**
 * Reads the current [NeverLateExtendedColors] — the urgency-color counterpart of
 * `MaterialTheme.colorScheme`, for roles Material 3 itself doesn't define. Usage mirrors
 * `MaterialTheme.colorScheme.xxx`: `NeverLateExtras.colors.calm`.
 */
object NeverLateExtras {
    val colors: NeverLateExtendedColors
        @Composable get() = LocalNeverLateExtendedColors.current
}

/**
 * App-wide theme. Wrap the UI in this so every Composable can read colours and
 * typography from [MaterialTheme].
 *
 * [dynamicColor] (feature 16) chooses between the brand [LightColorScheme]/[DarkColorScheme]
 * above and Android 12+'s wallpaper-driven Material You palette
 * (`dynamic{Light,Dark}ColorScheme`). It defaults to `false` (brand-first) here, matching the
 * persisted preference's own default — see [com.neverlate.data.UserPreferences.dynamicColor] —
 * so any caller that forgets to pass it explicitly still gets the brand identity rather than a
 * device-dependent one.
 */
@Composable
fun NeverLateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    // Dynamic-color mode has no brand-specific "calm/soon" equivalent to derive from the
    // wallpaper, so the urgency cue always uses the fixed brand tones from Color.kt regardless of
    // dynamicColor/darkTheme's other combinations — only light vs dark selects between them.
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalNeverLateExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
