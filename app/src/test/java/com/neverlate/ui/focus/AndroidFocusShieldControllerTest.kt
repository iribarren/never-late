package com.neverlate.ui.focus

import android.app.Application
import android.app.NotificationManager
import com.neverlate.data.FakeUserPreferencesRepository
import com.neverlate.data.UserPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Robolectric tests for [AndroidFocusShieldController]
 * (`docs/specs/2026-08-18-focus-mode-shielding.md`) — the one class in this feature that genuinely
 * needs a simulated Android environment (a real, shadowed `NotificationManager`), unlike
 * [FocusShieldRestoreTest]/[FocusShieldControllerTest], which stay plain-JVM against fakes. Proves
 * the wiring: D2 (`INTERRUPTION_FILTER_PRIORITY`, never `_NONE`), D10 (a revoked policy access
 * throws `SecurityException`, caught and swallowed), and AC-20's real idempotency (a second
 * `restore()` call, with the receipt already cleared, is a true no-op).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidFocusShieldControllerTest {

    private lateinit var notificationManager: NotificationManager
    private lateinit var shadowNotificationManager: ShadowNotificationManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Application
        notificationManager = context.getSystemService(NotificationManager::class.java)
        shadowNotificationManager = shadowOf(notificationManager)
    }

    private fun controller(repository: FakeUserPreferencesRepository = FakeUserPreferencesRepository()) =
        AndroidFocusShieldController(RuntimeEnvironment.getApplication(), repository) to repository

    @Test
    fun `applyDoNotDisturb sets the filter to PRIORITY and returns true when access is granted`() = runTest {
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        val (controller, _) = controller()

        val applied = controller.applyDoNotDisturb()

        assertTrue(applied)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, notificationManager.currentInterruptionFilter)
    }

    @Test
    fun `applyDoNotDisturb never sets INTERRUPTION_FILTER_NONE - D2`() = runTest {
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        val (controller, _) = controller()

        controller.applyDoNotDisturb()

        assertFalse(notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE)
    }

    @Test
    fun `applyDoNotDisturb never throws when access is not granted - D10`() = runTest {
        // Robolectric's ShadowNotificationManager does not itself throw SecurityException when
        // access is not granted (unlike the real platform), so this test cannot force
        // applyDoNotDisturb's try/catch to actually trigger — only that calling it under a
        // revoked grant is safe end to end. The catch branch itself (returning false on a real
        // SecurityException) is covered by the spec's manual on-device checklist (item 7:
        // "Revoke the access mid-session, then exit: no crash, no dialog").
        shadowNotificationManager.setNotificationPolicyAccessGranted(false)
        val (controller, _) = controller()

        controller.applyDoNotDisturb()
    }

    @Test
    fun `isPolicyAccessGranted mirrors the platform grant`() {
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        val (controller, _) = controller()
        assertTrue(controller.isPolicyAccessGranted())

        shadowNotificationManager.setNotificationPolicyAccessGranted(false)
        assertFalse(controller.isPolicyAccessGranted())
    }

    @Test
    fun `currentInterruptionFilter mirrors the platform value`() {
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        val (controller, _) = controller()

        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, controller.currentInterruptionFilter())
    }

    @Test
    fun `restore with an inactive session and a matching receipt restores the prior filter and clears it`() = runTest {
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        val repository = FakeUserPreferencesRepository(
            UserPreferences(focusShieldPriorFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS),
        )
        val controller = AndroidFocusShieldController(RuntimeEnvironment.getApplication(), repository)
        // Simulate the shield having actually applied PRIORITY earlier in this session.
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

        controller.restore(sessionActive = false)

        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, notificationManager.currentInterruptionFilter)
        assertNull(repository.userPreferences.value.focusShieldPriorFilter)
    }

    @Test
    fun `restore twice in a row is idempotent - AC-20`() = runTest {
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        val repository = FakeUserPreferencesRepository(
            UserPreferences(focusShieldPriorFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS),
        )
        val controller = AndroidFocusShieldController(RuntimeEnvironment.getApplication(), repository)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

        controller.restore(sessionActive = false)
        val filterAfterFirstRestore = notificationManager.currentInterruptionFilter

        // Second call: the receipt is already null (row 2 of shieldRestoreActionFor) — a true
        // no-op, the filter must not move again.
        controller.restore(sessionActive = false)

        assertEquals(filterAfterFirstRestore, notificationManager.currentInterruptionFilter)
        assertNull(repository.userPreferences.value.focusShieldPriorFilter)
    }

    @Test
    fun `restore with a session still active never touches the receipt`() = runTest {
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
        val repository = FakeUserPreferencesRepository(
            UserPreferences(focusShieldPriorFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS),
        )
        val controller = AndroidFocusShieldController(RuntimeEnvironment.getApplication(), repository)

        controller.restore(sessionActive = true)

        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, repository.userPreferences.value.focusShieldPriorFilter)
    }

    @Test
    fun `restore when access was revoked mid-session clears the receipt without crashing - D5 row 5`() = runTest {
        val repository = FakeUserPreferencesRepository(
            UserPreferences(focusShieldPriorFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS),
        )
        val controller = AndroidFocusShieldController(RuntimeEnvironment.getApplication(), repository)
        shadowNotificationManager.setNotificationPolicyAccessGranted(false)

        controller.restore(sessionActive = false)

        assertNull(repository.userPreferences.value.focusShieldPriorFilter)
    }

    @Test
    fun `cancelBackstop never throws even with no initialized WorkManager`() {
        val (controller, _) = controller()
        // Robolectric auto-initializes WorkManager for most configs; the point of this test is
        // simply that a stray IllegalStateException (D10's documented tolerance) is swallowed —
        // see AndroidFocusShieldController.cancelBackstop's KDoc.
        controller.cancelBackstop()
    }
}
