package com.neverlate.data.auth

import android.content.Context
import android.content.SharedPreferences
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit-tests the [createEncryptedPrefsWithRecovery] control flow that keeps a desynced Keystore
 * from crash-looping the app on launch (bug: `AEADBadTagException` from
 * `EncryptedSharedPreferences.create`). The real Keystore is untestable on the JVM, so recovery was
 * extracted as a pure function taking its `build`/`deleteCorruptState` dependencies as lambdas — we
 * drive those with fakes here. Robolectric only supplies a real, plain [SharedPreferences] instance
 * to use as an identity sentinel; no encryption is involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedPrefsRecoveryTest {

    private val goodPrefs: SharedPreferences =
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("sentinel", Context.MODE_PRIVATE)

    @Test
    fun `returns prefs directly when the store opens cleanly`() {
        var deleteCalls = 0

        val result = createEncryptedPrefsWithRecovery(
            build = { goodPrefs },
            deleteCorruptState = { deleteCalls++ },
        )

        assertSame(goodPrefs, result)
        assertEquals("must not touch the file when nothing is wrong", 0, deleteCalls)
    }

    @Test
    fun `on AEADBadTagException clears the corrupt file and rebuilds once`() {
        var builds = 0
        var deleteCalls = 0

        val result = createEncryptedPrefsWithRecovery(
            build = {
                builds++
                // AEADBadTagException is a GeneralSecurityException — the exact type the Keystore
                // desync surfaces as.
                if (builds == 1) throw AEADBadTagException("tag mismatch") else goodPrefs
            },
            deleteCorruptState = { deleteCalls++ },
        )

        assertSame(goodPrefs, result)
        assertEquals("rebuilt exactly once after clearing", 2, builds)
        assertEquals("deleted the corrupt file exactly once", 1, deleteCalls)
    }

    @Test
    fun `recovers from an IOException too`() {
        var builds = 0
        var deleted = false

        val result = createEncryptedPrefsWithRecovery(
            build = {
                builds++
                if (builds == 1) throw java.io.IOException("unreadable keyset") else goodPrefs
            },
            deleteCorruptState = { deleted = true },
        )

        assertSame(goodPrefs, result)
        assertTrue(deleted)
    }

    @Test
    fun `does not loop when even the fresh rebuild fails`() {
        var builds = 0
        var deleteCalls = 0

        assertThrows(AEADBadTagException::class.java) {
            createEncryptedPrefsWithRecovery(
                build = {
                    builds++
                    throw AEADBadTagException("still broken")
                },
                deleteCorruptState = { deleteCalls++ },
            )
        }

        assertEquals("one original attempt + one retry, no more", 2, builds)
        assertEquals(1, deleteCalls)
    }
}
