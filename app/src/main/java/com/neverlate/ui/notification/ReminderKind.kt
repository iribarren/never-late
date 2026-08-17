package com.neverlate.ui.notification

/**
 * The two kinds of one-shot alarm this app schedules per task, per the times-up-alert feature's
 * D1 decision. Introduced to fix a latent [android.app.PendingIntent] identity bug: before this
 * enum existed, both alarms for a task were built with the same request code and the same
 * (absent) [android.content.Intent] action, so `AlarmManager.setExactAndAllowWhileIdle`'s
 * `FLAG_UPDATE_CURRENT` silently treated the second alarm as a replacement for the first instead
 * of a second, independent one.
 *
 * [slot] namespaces [requestCodeFor]'s `Int` arithmetic; [action] makes the two `PendingIntent`s
 * distinct a second, independent way (belt and braces — see D1 in the feature spec). Both are
 * required at every one of the alarm-identity call sites: [AlarmManagerReminderScheduler],
 * [ReminderNotificationHelper], [ReminderSchedulingRepository], [ReminderReceiver],
 * `com.neverlate.ui.settings.SettingsViewModel` and [BootRescheduleWorker].
 */
enum class ReminderKind(val slot: Int, val action: String) {
    /** The existing feature-09 lead-time reminder: fires [minutesToMillis]-before a deadline. */
    LEAD_TIME(0, "com.neverlate.action.LEAD_TIME_REMINDER"),

    /** The times-up-alert feature's alarm: fires at the instant a task actually runs out of time. */
    TIME_UP(1, "com.neverlate.action.TIME_UP"),
}
