package com.neverlate.ui.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.neverlate.MainActivity
import com.neverlate.R
import com.neverlate.data.tasks.Task
import com.neverlate.data.tasks.formatDeadlineForDisplay
import java.util.Locale

/**
 * Id of the reminders channel — deliberately a **second**, separate channel from
 * [TASKS_NOTIFICATION_CHANNEL_ID]. That one is silent on purpose (a continuous status summary);
 * a reminder is the opposite, a one-shot alert, so it needs its own channel with its own
 * (unfrozen) importance — see [ensureChannel].
 */
const val REMINDER_NOTIFICATION_CHANNEL_ID = "task_reminders"

/**
 * Thin Android shell that turns one task's reminder into an actual [Notification], following the
 * same `ensureChannel`/`build*` split as [TasksNotificationHelper] — but posting to the *alerting*
 * channel above instead of the silent `tasks_pending` one.
 */
object ReminderNotificationHelper {

    /**
     * Creates the reminders channel, if it does not already exist.
     *
     * `IMPORTANCE_HIGH` is the whole point of this second channel: it is what makes Android show a
     * heads-up popup and play the channel's (default) sound — everything
     * [TasksNotificationHelper.ensureChannel] deliberately strips out of `tasks_pending`. As with
     * that channel, the importance is only honoured the **first** time this id is created; Android
     * freezes it afterwards so the user stays in control. Guarded by `SDK_INT >= O` for the same
     * reason as [TasksNotificationHelper.ensureChannel]: channels are an API 26+ concept.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannelCompat.Builder(
                REMINDER_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH,
            )
                .setName(context.getString(R.string.reminder_channel_name))
                .setDescription(context.getString(R.string.reminder_channel_description))
                .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }
    }

    /**
     * Builds the one-shot reminder notification for [task], which must have a [Task.deadline]
     * (the only kind of task this feature ever schedules a reminder for).
     *
     * [locale] drives [formatDeadlineForDisplay] so the deadline reads in the device's own
     * date/time conventions (feature 08); [now] is used only to compute how many whole minutes are
     * left, via a [plurals][R.plurals.reminder_notification_body] resource so the wording agrees
     * grammatically ("1 minuto" vs "3 minutos") — this is recomputed at the instant the
     * notification is actually built (inside [ReminderReceiver]), not read back from when the
     * alarm was scheduled, so it stays accurate even if the alarm fired a little late (US-5,
     * inexact fallback).
     */
    fun buildNotification(context: Context, task: Task, locale: Locale, now: Long): Notification {
        val deadline = requireNotNull(task.deadline) {
            "ReminderNotificationHelper.buildNotification requires a task with a deadline"
        }
        val minutesRemaining = ((deadline - now) / MILLIS_PER_MINUTE).coerceAtLeast(0L).toInt()
        val deadlineLabel = formatDeadlineForDisplay(deadline, locale)
        val body = context.resources.getQuantityString(
            R.plurals.reminder_notification_body,
            minutesRemaining,
            minutesRemaining,
            deadlineLabel,
        )

        return NotificationCompat.Builder(context, REMINDER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(task.title)
            .setContentText(body)
            // PRIORITY_HIGH is the pre-API-26 counterpart of the channel's IMPORTANCE_HIGH above —
            // on API 24-25 there is no channel, so this is what asks for a heads-up popup there.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // OQ-5 (approved): show the task title on the lock screen, same D3 call feature 06 made
            // for the continuous notification.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // A reminder is a one-shot alert, not an ongoing status: it should disappear once seen.
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent(context, task.id))
            .build()
    }

    /** Notification id for task [taskId]'s reminder — distinct from [TASKS_NOTIFICATION_ID] (1001)
     *  so several reminders (and the continuous summary) can all be visible at once. */
    fun notificationIdFor(taskId: Long): Int = requestCodeFor(taskId)

    /**
     * `PendingIntent` that opens [MainActivity] on the tasks list — reusing
     * [MainActivity.EXTRA_OPEN_TASKS], the same "open the app on tasks" recipe
     * [TasksNotificationHelper] and the pending-tasks widget already use, so tapping any of this
     * app's task surfaces always lands in the same place.
     */
    private fun buildContentIntent(context: Context, taskId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_TASKS, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCodeFor(taskId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
