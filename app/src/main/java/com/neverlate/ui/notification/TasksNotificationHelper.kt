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

/** Id of the one notification channel this feature ever posts to (API 26+, see [ensureChannel]). */
const val TASKS_NOTIFICATION_CHANNEL_ID = "tasks_pending"

/**
 * Id [TasksNotificationService] always reuses when calling `startForeground`/`notify` — reusing
 * the same id is what makes a later call **update the existing notification in place** instead of
 * posting a second one, exactly like [com.neverlate.ui.widget.PendingTasksWidget.updateAll]
 * redraws the same widget instance rather than creating a new one.
 */
const val TASKS_NOTIFICATION_ID = 1001

/**
 * Thin Android shell around [TasksNotificationModel]: turns that plain-Kotlin model into an
 * actual channel + [Notification], with all user-visible text coming from `res/values/strings.xml`
 * (never hardcoded). Kept as top-level functions (no class/object state) since every value they
 * need is either passed in or read fresh from [Context] — there is nothing to keep alive between
 * calls.
 */
object TasksNotificationHelper {

    /**
     * Creates this feature's notification channel, if it does not already exist.
     *
     * Notification channels are an **API 26+ concept**: before Android 8 there was no way for the
     * user to control a single app's notification categories independently, so every
     * `NotificationChannel` call is guarded by `SDK_INT >= O` — on API 24-25 (this project's
     * `minSdk`), [NotificationCompat] simply ignores the channel id passed to its `Builder` and
     * shows the notification without one.
     *
     * `IMPORTANCE_LOW` (no sound, no heads-up popup) matches this notification's job: an ongoing,
     * glanceable summary, not an urgent alert — re-posting it on every refresh must never buzz or
     * pop up over other apps. Calling this repeatedly is safe: creating a channel with the same id
     * again is a no-op that leaves the user's own channel settings (they can override importance
     * in system settings) untouched.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannelCompat.Builder(
                TASKS_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW,
            )
                .setName(context.getString(R.string.notification_channel_name))
                .setDescription(context.getString(R.string.notification_channel_description))
                .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }
    }

    /**
     * Builds the ongoing notification for [model], most-urgent tasks first.
     *
     * A few concepts new to this feature, all on this one `Notification`:
     * - `setOngoing(true)`: the notification cannot be swiped away while there is at least one
     *   pending task — matching D1/D2 (approved 2026-07-02), it should behave like a status
     *   indicator, not a dismissible alert.
     * - `setOnlyAlertOnce(true)`: re-posting with the same [TASKS_NOTIFICATION_ID] (from a write or
     *   the periodic worker) updates the content in place without sounding/vibrating again — only
     *   the very first post (if any) may alert.
     * - [NotificationCompat.InboxStyle]: the multi-line expanded layout used to list every visible
     *   row, mirroring the widget's list but adapted to the notification shade.
     * - `setVisibility`/`setPublicVersion`: see the two below for the lockscreen privacy story.
     */
    fun buildNotification(context: Context, model: TasksNotificationModel.Content): Notification {
        val mostUrgentLabel = remainingLabel(context, model.mostUrgent)

        val inboxStyle = NotificationCompat.InboxStyle()
        model.rows.forEach { row ->
            inboxStyle.addLine(context.getString(R.string.notification_row_format, row.title, remainingLabel(context, row)))
        }

        return NotificationCompat.Builder(context, TASKS_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(
                context.getString(R.string.notification_content_text_format, model.mostUrgent.title, mostUrgentLabel),
            )
            .setStyle(inboxStyle)
            .setNumber(model.totalPendingCount)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // D3 (approved 2026-07-02): VISIBILITY_PUBLIC for now — task titles show directly on
            // the lockscreen. VISIBILITY_PUBLIC makes Android ignore setPublicVersion below (it
            // only takes effect under VISIBILITY_PRIVATE/VISIBILITY_SECRET), so the redacted
            // version is built and wired up but currently inactive. Flipping this single line to
            // NotificationCompat.VISIBILITY_PRIVATE is all a future feature 07 "hide titles on the
            // lockscreen" setting would need to do to activate it.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(buildPublicVersion(context, model, mostUrgentLabel))
            .setContentIntent(buildContentIntent(context))
            .build()
    }

    /**
     * The redacted notification handed to [NotificationCompat.Builder.setPublicVersion]: a count
     * and the most urgent remaining time, **no task titles** — see US-5 in the feature spec. Only
     * rendered by the system when the main notification's visibility is
     * [NotificationCompat.VISIBILITY_PRIVATE] (see the comment in [buildNotification]); with the
     * current D3 = `VISIBILITY_PUBLIC` this is built but never actually shown, kept ready for
     * feature 07 to flip on as a user preference.
     */
    private fun buildPublicVersion(
        context: Context,
        model: TasksNotificationModel.Content,
        mostUrgentLabel: String,
    ): Notification =
        NotificationCompat.Builder(context, TASKS_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(
                context.getString(R.string.notification_public_summary_format, model.totalPendingCount, mostUrgentLabel),
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /**
     * A minimal, never-actually-seen [Notification] used only to satisfy the foreground-service
     * contract when [TasksNotificationModel] turns out to be `Empty` — see
     * [TasksNotificationService] for why a call to `startForeground` is unavoidable even then.
     */
    fun buildEmptyPlaceholder(context: Context): Notification =
        NotificationCompat.Builder(context, TASKS_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_title))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    /**
     * `PendingIntent` that opens [MainActivity] straight on the tasks list — reusing
     * [MainActivity.EXTRA_OPEN_TASKS], the exact same mechanism [com.neverlate.ui.widget.PendingTasksWidget]
     * already uses for its own tap action, so there is only one "open the app on tasks" recipe in
     * the whole app instead of two subtly different ones.
     *
     * `FLAG_IMMUTABLE` is required from API 31 onward for any `PendingIntent` handed to the system
     * (here, `NotificationManager`) that the app itself does not need to mutate later.
     * `FLAG_ACTIVITY_NEW_TASK` is set on the [Intent] itself (not a `PendingIntent` flag) because
     * this `PendingIntent` is built from a non-Activity context ([TasksNotificationService]); the
     * flag tells Android to start `MainActivity` in a fresh task if the app is not already running.
     */
    private fun buildContentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_TASKS, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** A row's remaining-time label: [R.string.tasks_time_up] once timed out, its countdown otherwise. */
    private fun remainingLabel(context: Context, row: com.neverlate.domain.tasks.PendingTaskRow): String =
        if (row.isTimedOut) context.getString(R.string.tasks_time_up) else row.remaining
}
