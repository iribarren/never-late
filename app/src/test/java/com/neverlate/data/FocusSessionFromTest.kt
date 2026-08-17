package com.neverlate.data

import com.neverlate.domain.tasks.FocusSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JVM tests for [focusSessionFrom] (Modo Foco, D6 of `docs/specs/2026-08-18-focus-mode.md`)
 * — the tolerant parser [DataStoreUserPreferencesRepository] reads the three `focus_*` DataStore
 * keys through. Every fallback here must resolve toward "easier to leave", never toward "harder"
 * (AC-8, AC-9): a corrupted/missing preference is the lenient default, never a crash and never a
 * state the session is harder to end from.
 */
class FocusSessionFromTest {

    @Test
    fun `a zero startedAt means no session, regardless of the other two values`() {
        assertNull(focusSessionFrom(startedAt = 0L, exitCode = "1234", rosterCsv = "1,2,3"))
    }

    @Test
    fun `a negative startedAt also means no session`() {
        assertNull(focusSessionFrom(startedAt = -1L, exitCode = "1234", rosterCsv = "1"))
    }

    @Test
    fun `a positive startedAt with a blank code and empty roster still yields a session`() {
        val session = focusSessionFrom(startedAt = 5_000L, exitCode = "", rosterCsv = "")

        assertEquals(FocusSession(startedAt = 5_000L, exitCode = "", roster = emptySet()), session)
    }

    @Test
    fun `a well-formed roster parses every id`() {
        val session = focusSessionFrom(startedAt = 1_000L, exitCode = "4321", rosterCsv = "1,2,3")

        assertEquals(setOf(1L, 2L, 3L), session?.roster)
        assertEquals("4321", session?.exitCode)
    }

    @Test
    fun `a single corrupted id is dropped without discarding the rest of the roster`() {
        // AC-9: "abc" is not a valid Long — it must not invalidate 1 and 3 alongside it.
        val session = focusSessionFrom(startedAt = 1_000L, exitCode = "1234", rosterCsv = "1,abc,3")

        assertEquals(setOf(1L, 3L), session?.roster)
    }

    @Test
    fun `blank entries from stray commas are dropped silently`() {
        val session = focusSessionFrom(startedAt = 1_000L, exitCode = "1234", rosterCsv = "1,,3,")

        assertEquals(setOf(1L, 3L), session?.roster)
    }

    @Test
    fun `a roster that is entirely unparseable yields an empty set, not null`() {
        val session = focusSessionFrom(startedAt = 1_000L, exitCode = "1234", rosterCsv = "not,a,valid,list")

        assertEquals(emptySet<Long>(), session?.roster)
    }
}
