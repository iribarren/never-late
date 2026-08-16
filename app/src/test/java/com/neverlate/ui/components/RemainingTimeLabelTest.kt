package com.neverlate.ui.components

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifies the thin `Context`/`NumberFormat`/`getString` mapping [formatRemainingLabel] adds on
 * top of the pure [com.neverlate.domain.tasks.remainingTimeFor] classifier. The *branching* (which
 * label shape applies) is already exhaustively covered, with no `Context` needed, by
 * [com.neverlate.domain.tasks.RemainingTimeTest] — this class only proves the rendering step:
 * resource lookup per locale (`values/` vs `values-en/`) and that numbers go through
 * `NumberFormat` rather than `Long.toString()`.
 *
 * Robolectric is already a test dependency of this module (see `OutboxTaskRepositoryTest`,
 * `DataStoreUserPreferencesRepositoryTest`, etc., all of which pull a `Context` from
 * `RuntimeEnvironment.getApplication()`); this test follows the same pattern rather than adding a
 * new one. `Context.createConfigurationContext` is the standard Android way to get a `Context`
 * scoped to a specific `Locale`'s resources, independent of the host JVM's default `Locale` — the
 * same thing `formatRemainingLabel` itself reads via `context.resources.configuration.locales[0]`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemainingTimeLabelTest {

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
    }

    private fun contextFor(locale: Locale): Context {
        val base = RuntimeEnvironment.getApplication()
        val configuration = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun `exact zero renders the shared time-up resource, in Spanish and English`() {
        assertEquals("Tiempo agotado", formatRemainingLabel(contextFor(Locale.forLanguageTag("es")), 0L))
        assertEquals("Time's up", formatRemainingLabel(contextFor(Locale.forLanguageTag("en")), 0L))
    }

    @Test
    fun `hours and minutes render through the placeholder resource, in Spanish and English`() {
        val remainingMillis = 2 * HOUR + 38 * MINUTE // 2h 38m

        assertEquals("2h 38m", formatRemainingLabel(contextFor(Locale.forLanguageTag("es")), remainingMillis))
        assertEquals("2h 38m", formatRemainingLabel(contextFor(Locale.forLanguageTag("en")), remainingMillis))
    }

    @Test
    fun `days, hours and minutes always render all three parts, in Spanish and English`() {
        val remainingMillis = 36 * HOUR + 10 * MINUTE // 1d 12h 10m

        assertEquals("1d 12h 10m", formatRemainingLabel(contextFor(Locale.forLanguageTag("es")), remainingMillis))
        assertEquals("1d 12h 10m", formatRemainingLabel(contextFor(Locale.forLanguageTag("en")), remainingMillis))
    }

    @Test
    fun `the zero-hour-part days case still renders all three parts`() {
        val remainingMillis = DAY + 30 * MINUTE // 1d 0h 30m

        assertEquals("1d 0h 30m", formatRemainingLabel(contextFor(Locale.forLanguageTag("es")), remainingMillis))
    }

    @Test
    fun `numbers are rendered via NumberFormat with locale-specific grouping, not raw digit concatenation`() {
        // A day count large enough to trigger a thousands separator makes it observable that the
        // digits go through NumberFormat rather than Long.toString(): German and US NumberFormat
        // disagree on the grouping character ("." vs ",") for the exact same value, which could
        // only differ if the number were actually being formatted per-Locale.
        val remainingMillis = 1234L * DAY + 5 * HOUR + 6 * MINUTE // 1234d 5h 6m

        val german = formatRemainingLabel(contextFor(Locale.GERMANY), remainingMillis)
        val us = formatRemainingLabel(contextFor(Locale.US), remainingMillis)

        assertEquals("1.234d 5h 6m", german)
        assertEquals("1,234d 5h 6m", us)
    }
}
