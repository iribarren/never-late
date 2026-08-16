package com.neverlate.domain.tasks

import com.neverlate.data.tasks.durationParts

/**
 * The *shape* of a compact remaining-time label — time-up, under a minute, minutes-only,
 * exact hours, hours+minutes, or days+hours+minutes — with no letters, separators, `Context` or
 * `Locale` attached. Deciding *which* shape applies is a pure rule (see [remainingTimeFor]);
 * turning a shape into localized text is a presentation concern that belongs to
 * [com.neverlate.ui.components.formatRemainingLabel] instead. Keeping the two apart is what makes
 * the branching itself JVM-testable without an Android `Context`.
 */
sealed interface RemainingTime {
    /** Exactly zero remaining: the countdown has run out. */
    data object TimeUp : RemainingTime

    /** More than zero but less than a full minute (`1 s … 59 s`) — shown as `<1m`, not `0m`. */
    data object UnderMinute : RemainingTime

    /** Less than an hour left, at least one whole minute: `h == 0, m > 0`. */
    data class Minutes(val minutes: Long) : RemainingTime

    /** A whole number of hours with no leftover minutes, under a day: `0 < h < 24, m == 0`. */
    data class Hours(val hours: Long) : RemainingTime

    /** Both an hour and a minute part, under a day: `0 < h < 24, m > 0`. */
    data class HoursMinutes(val hours: Long, val minutes: Long) : RemainingTime

    /**
     * A day or more remaining (`h >= 24`). Always carries all three parts, even zero ones
     * (`2d 0h 0m`, `1d 0h 30m`) — see the Format matrix in the feature spec for why the days tier
     * never drops a zero part the way the sub-day tiers do.
     */
    data class DaysHoursMinutes(val days: Long, val hours: Long, val minutes: Long) : RemainingTime
}

/**
 * Classifies [remainingMillis] into the [RemainingTime] shape that should be shown, per the
 * feature spec's Format matrix. Reuses [durationParts] for the floor-to-minutes split (never a
 * new division) — flooring, not rounding, is what keeps the on-screen number monotonically
 * non-increasing as time passes. Days are derived from the hour part: `d = h / 24`,
 * `hOfDay = h % 24`.
 */
fun remainingTimeFor(remainingMillis: Long): RemainingTime {
    if (remainingMillis == 0L) return RemainingTime.TimeUp
    if (remainingMillis < 60_000L) return RemainingTime.UnderMinute

    val (hours, minutes) = durationParts(remainingMillis)
    return when {
        hours >= 24 -> RemainingTime.DaysHoursMinutes(
            days = hours / 24,
            hours = hours % 24,
            minutes = minutes,
        )
        hours == 0L -> RemainingTime.Minutes(minutes)
        minutes == 0L -> RemainingTime.Hours(hours)
        else -> RemainingTime.HoursMinutes(hours, minutes)
    }
}
