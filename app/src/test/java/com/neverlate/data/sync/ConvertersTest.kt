package com.neverlate.data.sync

import com.neverlate.data.tasks.Priority
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the [Converters] `@TypeConverter`s (feature 13b focus: [Priority]). These run on
 * the plain JVM — a `@TypeConverter` is just a pure function, so it needs no Room database or
 * Android to test. The important case is the **tolerant** read: an unknown stored value must never
 * crash a query, it must fall back to a safe default (the same forward-compat guarantee the wire
 * side makes, see the API contract §5).
 */
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun priority_roundTrips_throughItsStoredName() {
        for (priority in Priority.entries) {
            val stored = converters.fromPriority(priority)
            assertEquals(priority.name, stored)
            assertEquals(priority, converters.toPriority(stored))
        }
    }

    @Test
    fun toPriority_unknownValue_fallsBackToNone() {
        // A value this version of the app no longer recognises (e.g. a constant removed in a future
        // rename, or an older client that sent something else) must decode to NONE, not throw.
        assertEquals(Priority.NONE, converters.toPriority("CRITICAL"))
        assertEquals(Priority.NONE, converters.toPriority(""))
        assertEquals(Priority.NONE, converters.toPriority("none")) // case-sensitive: only NONE matches
    }
}
