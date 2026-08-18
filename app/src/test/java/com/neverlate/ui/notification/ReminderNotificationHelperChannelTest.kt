package com.neverlate.ui.notification

import android.app.NotificationManager
import com.neverlate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [ReminderNotificationHelper.ensureChannel]'s Modo Foco blindaje addition
 * (`docs/specs/2026-08-18-focus-mode-shielding.md`, D3/AC-14): `bypassDnd` is set on the
 * `task_reminders` channel only when `ACCESS_NOTIFICATION_POLICY` is currently granted, and never
 * on the silent `tasks_pending` channel (there is no channel to touch there in the first place —
 * this file only ever creates `task_reminders`). A real, shadowed `NotificationManager` is needed
 * because `NotificationChannel.canBypassDnd()` reflects the platform's own channel state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderNotificationHelperChannelTest {

    private val context = RuntimeEnvironment.getApplication()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `ensureChannel sets bypassDnd when policy access is granted`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)

        ReminderNotificationHelper.ensureChannel(context)

        val channel = notificationManager.getNotificationChannel(REMINDER_NOTIFICATION_CHANNEL_ID)
        assertTrue(channel.canBypassDnd())
    }

    @Test
    fun `ensureChannel never attempts bypassDnd when policy access is not granted`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)

        ReminderNotificationHelper.ensureChannel(context)

        val channel = notificationManager.getNotificationChannel(REMINDER_NOTIFICATION_CHANNEL_ID)
        assertFalse(channel.canBypassDnd())
    }

    @Test
    fun `ensureChannel keeps IMPORTANCE_HIGH and the expected name-description regardless of access`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)

        ReminderNotificationHelper.ensureChannel(context)

        val channel = notificationManager.getNotificationChannel(REMINDER_NOTIFICATION_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(context.getString(R.string.reminder_channel_name), channel.name)
        assertEquals(context.getString(R.string.reminder_channel_description), channel.description)
    }
}
