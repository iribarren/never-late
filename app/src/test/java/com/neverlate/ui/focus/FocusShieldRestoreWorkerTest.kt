package com.neverlate.ui.focus

import com.neverlate.data.FakeUserPreferencesRepository
import com.neverlate.data.UserPreferences
import com.neverlate.domain.tasks.FOCUS_SESSION_MAX_AGE_MILLIS
import com.neverlate.domain.tasks.FocusSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [FocusShieldRestoreWorker] (`docs/specs/2026-08-18-focus-mode-shielding.md`, D6) —
 * against [runFocusShieldRestoreCheck] (the pure decision [doWork] delegates to, AC-19) and a
 * [FakeFocusShieldController], plus [FocusShieldRestoreWorker.buildBackstopRequest] (AC-18). No
 * real `WorkManager` instance is touched anywhere in this file — constructing a
 * [androidx.work.OneTimeWorkRequest] via its builder needs no Android system service, and
 * [runFocusShieldRestoreCheck] only depends on the two interfaces already faked elsewhere in this
 * suite (this project has no `work-testing` dependency, so [FocusShieldRestoreWorker.enqueue]'s own
 * call to `WorkManager.getInstance(context).enqueueUniqueWork(...)` is not exercised here — see the
 * PR description's manual checklist / AC-17 note).
 */
class FocusShieldRestoreWorkerTest {

    @Test
    fun `the backstop request's initial delay is the session's own expiry constant - AC-18`() {
        val request = FocusShieldRestoreWorker.buildBackstopRequest()

        assertEquals(FOCUS_SESSION_MAX_AGE_MILLIS, request.workSpec.initialDelay)
    }

    @Test
    fun `an active session leaves the receipt untouched - restore does nothing`() = runTest {
        val controller = FakeFocusShieldController()
        val session = FocusSession(startedAt = System.currentTimeMillis(), exitCode = "1234", roster = emptySet())
        val repository = FakeUserPreferencesRepository(UserPreferences(focusSession = session))

        runFocusShieldRestoreCheck(controller, repository)

        assertEquals(listOf(true), controller.restoreCalls)
    }

    @Test
    fun `an expired or absent session runs the restore as inactive`() = runTest {
        val controller = FakeFocusShieldController()
        val repository = FakeUserPreferencesRepository(UserPreferences(focusSession = null))

        runFocusShieldRestoreCheck(controller, repository)

        assertEquals(listOf(false), controller.restoreCalls)
    }

    @Test
    fun `running the check twice in a row calls restore with the same sessionActive value both times`() = runTest {
        // AC-20's real idempotency guarantee — the second restore() call being a true no-op once
        // the receipt is already cleared — lives in shieldRestoreActionFor's row 2 (proven in
        // FocusShieldRestoreTest) and in AndroidFocusShieldController's wiring of it (proven with
        // a real, Robolectric-shadowed NotificationManager in AndroidFocusShieldControllerTest).
        // This test only proves runFocusShieldRestoreCheck itself calls restore() consistently
        // across repeated invocations, with no accumulated state of its own.
        val controller = FakeFocusShieldController()
        val repository = FakeUserPreferencesRepository(UserPreferences(focusSession = null))

        runFocusShieldRestoreCheck(controller, repository)
        runFocusShieldRestoreCheck(controller, repository)

        assertEquals(listOf(false, false), controller.restoreCalls)
    }
}
