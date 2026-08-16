package com.neverlate.ui.components

import android.content.Context
import com.neverlate.R
import com.neverlate.domain.tasks.RemainingTime
import com.neverlate.domain.tasks.remainingTimeFor
import java.text.NumberFormat

/**
 * The single home of remaining-time *presentation* — mirrors how `durationLabel` in
 * `ui/tasks/TasksScreen.kt` is the single home of estimated-duration presentation. Turns raw
 * [remainingMillis] into the compact, localizable countdown label (`2h 38m`, `1d 12h 10m`, `<1m`,
 * `tasks_time_up`, ...) and is called by all three surfaces that show a countdown — the task card,
 * the home-screen widget and the lock-screen notification — so they can never drift on wording.
 *
 * Takes a plain [Context] rather than being `@Composable`: the widget (Glance) and the
 * notification helper have no composition to read `LocalContext`/`LocalConfiguration` from, so
 * every caller passes its own `Context` explicitly (`LocalContext.current` from Compose call
 * sites). The *branching* — which kind of label this is — is delegated to the pure,
 * JVM-testable [remainingTimeFor]; this function only maps that result to localized text via
 * [NumberFormat.getIntegerInstance] for the digits and string resources for the unit letters and
 * word order, exactly the pattern `durationLabel` already proved.
 */
fun formatRemainingLabel(context: Context, remainingMillis: Long): String {
    val locale = context.resources.configuration.locales[0]
    val numberFormat = NumberFormat.getIntegerInstance(locale)

    return when (val remaining = remainingTimeFor(remainingMillis)) {
        RemainingTime.TimeUp -> context.getString(R.string.tasks_time_up)
        RemainingTime.UnderMinute -> context.getString(R.string.tasks_remaining_under_minute)
        is RemainingTime.Minutes -> context.getString(
            R.string.tasks_remaining_minutes,
            numberFormat.format(remaining.minutes),
        )
        is RemainingTime.Hours -> context.getString(
            R.string.tasks_remaining_hours,
            numberFormat.format(remaining.hours),
        )
        is RemainingTime.HoursMinutes -> context.getString(
            R.string.tasks_remaining_hours_minutes,
            numberFormat.format(remaining.hours),
            numberFormat.format(remaining.minutes),
        )
        is RemainingTime.DaysHoursMinutes -> context.getString(
            R.string.tasks_remaining_days_hours_minutes,
            numberFormat.format(remaining.days),
            numberFormat.format(remaining.hours),
            numberFormat.format(remaining.minutes),
        )
    }
}
