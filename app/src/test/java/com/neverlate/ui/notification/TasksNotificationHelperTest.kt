package com.neverlate.ui.notification

import android.app.Notification
import com.neverlate.domain.tasks.PendingTaskRow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * AC-4 (spec `widget-adaptive-layout`, D2): [PendingTaskRow] gained `id` and `totalMillis` for the
 * widget's exclusive benefit, and the lock-screen notification must render byte-for-byte the same
 * output whether or not those two fields are populated — it never reads them (see
 * [TasksNotificationHelper.buildNotification]'s private `notificationRowLine`/`remainingLabel`,
 * which only touch `title`/`remainingMillis`/`priority`). This is a Robolectric test, not a plain
 * JVM one, because building a real [Notification] needs a [android.content.Context] for its
 * string resources.
 */
@RunWith(RobolectricTestRunner::class)
class TasksNotificationHelperTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `buildNotification ignores id and totalMillis on every row`() {
        val withoutWidgetFields = PendingTaskRow(title = "Leer", remainingMillis = 5 * 60_000L)
        val withWidgetFields = PendingTaskRow(
            title = "Leer",
            remainingMillis = 5 * 60_000L,
            id = 42L,
            totalMillis = 30 * 60_000L,
        )

        val plainModel = TasksNotificationModel.Content(rows = listOf(withoutWidgetFields), totalPendingCount = 1)
        val widgetFieldsModel = TasksNotificationModel.Content(rows = listOf(withWidgetFields), totalPendingCount = 1)

        val plainNotification = TasksNotificationHelper.buildNotification(context, plainModel)
        val widgetFieldsNotification = TasksNotificationHelper.buildNotification(context, widgetFieldsModel)

        assertEquals(
            plainNotification.extras.getCharSequence(Notification.EXTRA_TEXT),
            widgetFieldsNotification.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertEquals(
            plainNotification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.toList(),
            widgetFieldsNotification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.toList(),
        )
    }
}
