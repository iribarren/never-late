package com.neverlate.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ThemeMode.fromStorage], the tolerant parser that turns the persisted string
 * back into an enum. A missing or unrecognised value must fall back to [ThemeMode.SYSTEM] and
 * never throw.
 */
class ThemeModeTest {

    @Test
    fun `each known name maps back to its enum`() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStorage("LIGHT"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorage("DARK"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("SYSTEM"))
    }

    @Test
    fun `null falls back to SYSTEM`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage(null))
    }

    @Test
    fun `unknown value falls back to SYSTEM`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("PURPLE"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage(""))
        // Parsing is case-sensitive on the stored name; anything that isn't an exact match is SYSTEM.
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("dark"))
    }
}
