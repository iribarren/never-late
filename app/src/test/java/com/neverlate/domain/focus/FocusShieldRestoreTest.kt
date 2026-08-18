package com.neverlate.domain.focus

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [shieldRestoreActionFor] (`docs/specs/2026-08-18-focus-mode-shielding.md`, D5) —
 * every row of the six-row restoration state machine, plus the boundary between rows 3 and 4.
 * No Android import anywhere in this file or [FocusShieldRestore.kt] — provably JVM-testable
 * without Robolectric (AC-7).
 */
class FocusShieldRestoreTest {

    private val applied = 2 // stand-in for INTERRUPTION_FILTER_PRIORITY — see the sut's own KDoc.
    private val unknown = 0 // stand-in for INTERRUPTION_FILTER_UNKNOWN.
    private val somethingElse = 3 // stand-in for a filter the person set themselves.

    // Row 1 -------------------------------------------------------------------------------------

    @Test
    fun `a running session always returns None, regardless of the receipt`() {
        assertEquals(
            ShieldRestoreAction.None,
            shieldRestoreActionFor(
                sessionActive = true,
                priorFilter = null,
                currentFilter = applied,
                appliedFilter = applied,
                policyAccessGranted = true,
            ),
        )
        assertEquals(
            "a live session keeps its shield even with a present receipt",
            ShieldRestoreAction.None,
            shieldRestoreActionFor(
                sessionActive = true,
                priorFilter = 1,
                currentFilter = unknown,
                appliedFilter = applied,
                policyAccessGranted = false,
            ),
        )
    }

    // Row 2 -------------------------------------------------------------------------------------

    @Test
    fun `an inactive session with no receipt returns None`() {
        assertEquals(
            ShieldRestoreAction.None,
            shieldRestoreActionFor(
                sessionActive = false,
                priorFilter = null,
                currentFilter = applied,
                appliedFilter = applied,
                policyAccessGranted = true,
            ),
        )
    }

    // Row 3 -------------------------------------------------------------------------------------

    @Test
    fun `an inactive session with a matching current filter restores the prior filter`() {
        assertEquals(
            ShieldRestoreAction.RestoreFilter(filter = 7),
            shieldRestoreActionFor(
                sessionActive = false,
                priorFilter = 7,
                currentFilter = applied,
                appliedFilter = applied,
                policyAccessGranted = true,
            ),
        )
    }

    // Row 4 -------------------------------------------------------------------------------------

    @Test
    fun `a current filter that no longer matches the applied one clears the receipt only`() {
        assertEquals(
            "the person changed DND themselves — their choice wins, our receipt is forgotten",
            ShieldRestoreAction.ClearReceiptOnly,
            shieldRestoreActionFor(
                sessionActive = false,
                priorFilter = 7,
                currentFilter = somethingElse,
                appliedFilter = applied,
                policyAccessGranted = true,
            ),
        )
    }

    @Test
    fun `the row 3 to row 4 boundary is exactly equality with the applied filter`() {
        // One tick below the applied value: still row 3 (equal).
        assertEquals(
            ShieldRestoreAction.RestoreFilter(filter = 5),
            shieldRestoreActionFor(
                sessionActive = false,
                priorFilter = 5,
                currentFilter = applied,
                appliedFilter = applied,
                policyAccessGranted = true,
            ),
        )
        // Any other value at all: row 4.
        assertEquals(
            ShieldRestoreAction.ClearReceiptOnly,
            shieldRestoreActionFor(
                sessionActive = false,
                priorFilter = 5,
                currentFilter = applied + 1,
                appliedFilter = applied,
                policyAccessGranted = true,
            ),
        )
    }

    // Row 5 -------------------------------------------------------------------------------------

    @Test
    fun `a revoked policy access clears the receipt only, regardless of the current filter`() {
        assertEquals(
            ShieldRestoreAction.ClearReceiptOnly,
            shieldRestoreActionFor(
                sessionActive = false,
                priorFilter = 7,
                currentFilter = applied,
                appliedFilter = applied,
                policyAccessGranted = false,
            ),
        )
        assertEquals(
            "row 5 wins over row 6's UNKNOWN check when access is also missing",
            ShieldRestoreAction.ClearReceiptOnly,
            shieldRestoreActionFor(
                sessionActive = false,
                priorFilter = 7,
                currentFilter = unknown,
                appliedFilter = applied,
                policyAccessGranted = false,
            ),
        )
    }

    // Row 6 -------------------------------------------------------------------------------------

    @Test
    fun `an unknown current filter keeps the receipt and returns None`() {
        assertEquals(
            ShieldRestoreAction.None,
            shieldRestoreActionFor(
                sessionActive = false,
                priorFilter = 7,
                currentFilter = unknown,
                appliedFilter = applied,
                policyAccessGranted = true,
            ),
        )
    }

    // AC-7 sanity: the function is a plain top-level fun with no side effects — calling it twice
    // with the same arguments always yields the same, structurally-equal result.
    @Test
    fun `is a pure function - identical arguments always yield an equal result`() {
        val first = shieldRestoreActionFor(false, 7, applied, applied, true)
        val second = shieldRestoreActionFor(false, 7, applied, applied, true)
        assertEquals(first, second)
    }
}
