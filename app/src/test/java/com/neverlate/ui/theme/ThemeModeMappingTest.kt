package com.neverlate.ui.theme

import com.neverlate.data.ThemeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [themeModeToDark], the pure function that resolves a [ThemeMode] preference into
 * the light/dark boolean the theme consumes. LIGHT/DARK ignore the system; SYSTEM defers to it.
 */
class ThemeModeMappingTest {

    @Test
    fun `LIGHT is always light regardless of system`() {
        assertFalse(themeModeToDark(ThemeMode.LIGHT, systemInDark = false))
        assertFalse(themeModeToDark(ThemeMode.LIGHT, systemInDark = true))
    }

    @Test
    fun `DARK is always dark regardless of system`() {
        assertTrue(themeModeToDark(ThemeMode.DARK, systemInDark = false))
        assertTrue(themeModeToDark(ThemeMode.DARK, systemInDark = true))
    }

    @Test
    fun `SYSTEM follows the device setting`() {
        assertFalse(themeModeToDark(ThemeMode.SYSTEM, systemInDark = false))
        assertTrue(themeModeToDark(ThemeMode.SYSTEM, systemInDark = true))
    }
}
