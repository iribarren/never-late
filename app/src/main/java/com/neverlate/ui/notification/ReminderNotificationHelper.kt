package com.neverlate.ui.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
     *
     * Modo Foco blindaje (`docs/specs/2026-08-18-focus-mode-shielding.md`, D3/AC-14): built as a
     * platform [NotificationChannel] rather than through `NotificationChannelCompat.Builder` (the
     * builder used before this feature), because only the platform type exposes
     * `setBypassDnd(true)` — the flag that lets this channel's alerts survive a Modo Foco
     * session's `INTERRUPTION_FILTER_PRIORITY` (D2 never sets `INTERRUPTION_FILTER_NONE`, which
     * would suppress alarms too; a bypassing channel is how the app's own reminders stay a
     * priority interruption without touching the device's global policy). Setting the flag is
     * guarded by [android.app.NotificationManager.isNotificationPolicyAccessGranted] — the
     * platform silently refuses it without that special access, so it must never be attempted
     * unguarded (D3). **Two accepted, documented limits, not bugs to chase**: on an install that
     * already created this channel before this update, Android may keep `bypassDnd = false` since
     * channel settings are user-owned after first creation — this app never deletes and recreates
     * the channel to force it (that would reset every preference the person set on it, see D3);
     * and API 24-25 have no channels at all, so `_PRIORITY` there silences this channel exactly
     * like it silences anything else not in the global policy's priority categories.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                REMINDER_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.reminder_channel_description)
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                if (notificationManager?.isNotificationPolicyAccessGranted == true) {
                    setBypassDnd(true)
                }
            }
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
        return baseBuilder(context, task, body).build()
    }

    /**
     * Builds the times-up-alert feature's notification for [task], fired the instant its time
     * actually runs out (see `domain/tasks/TimeUpPlanning.kt`'s `timeUpInstantFor`).
     *
     * Unlike [buildNotification], this needs no [locale] and no `now`: its body is the fixed,
     * already-localized [R.string.tasks_time_up] — deliberately timeless text, so a late delivery
     * (D5, inexact-alarm degradation) can never make it read as wrong the way a countdown-flavoured
     * "in N minutes" string could.
     */
    fun buildTimeUpNotification(context: Context, task: Task): Notification =
        baseBuilder(context, task, context.getString(R.string.tasks_time_up)).build()

    /**
     * The chrome shared by both notification kinds, factored out so [buildNotification] and
     * [buildTimeUpNotification] cannot drift apart (icon, priority, visibility, auto-cancel and the
     * tap target are identical between the two — only the channel is already shared and the body
     * text differs).
     */
    private fun baseBuilder(context: Context, task: Task, body: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, REMINDER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(task.title)
            .setContentText(body)
            // PRIORITY_HIGH is the pre-API-26 counterpart of the channel's IMPORTANCE_HIGH above —
            // on API 24-25 there is no channel, so this is what asks for a heads-up popup there.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // OQ-5 (approved): show the task title on the lock screen, same D3 call feature 06 made
            // for the continuous notification.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Lets the OS classify this correctly under Do Not Disturb.
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // A reminder is a one-shot alert, not an ongoing status: it should disappear once seen.
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent(context, task.id))

    /** Notification id for task [taskId]'s [kind] alarm — offset well past [TASKS_NOTIFICATION_ID]
     *  (1001) so several reminders (of either kind) and the continuous summary can all be visible
     *  at once, with no collision (D2). */
    fun notificationIdFor(taskId: Long, kind: ReminderKind): Int =
        REMINDER_NOTIFICATION_ID_BASE + requestCodeFor(taskId, kind)

    /**
     * `PendingIntent` that opens [MainActivity] on the tasks list — reusing
     * [MainActivity.EXTRA_OPEN_TASKS], the same "open the app on tasks" recipe
     * [TasksNotificationHelper] and the pending-tasks widget already use, so tapping any of this
     * app's task surfaces always lands in the same place.
     *
     * Keyed only by [taskId] (not by [ReminderKind]) — tapping either kind's notification should
     * always land on the same content intent, and content `PendingIntent`s are independent of the
     * alarm `PendingIntent`s namespaced in [ReminderScheduler.kt].
     */
    private fun buildContentIntent(context: Context, taskId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_TASKS, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/**
 * Base offset for reminder notification ids (D2) — chosen well clear of [TASKS_NOTIFICATION_ID]
 * (1001), which the un-namespaced `requestCodeFor(taskId)` used to collide with for task id 1001,
 * and which a bare `taskId * 2 (+1)` would newly collide with for task ids 500/501.
 */
private const val REMINDER_NOTIFICATION_ID_BASE = 10_000

private const val MILLIS_PER_MINUTE = 60_000L
