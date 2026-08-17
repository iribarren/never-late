package com.neverlate.domain.tasks

import com.neverlate.data.tasks.Priority

/**
 * The shared decision behind "what color does this urgency/priority mean" — a pure **name**, never
 * a `Color`/`ColorProvider`. Feature 05b (`docs/specs/2026-08-17-widget-visual-refresh.md`) built
 * two worlds that each need to turn an [UrgencyLevel]/[Priority] into a color: Compose
 * (`ui/tasks/TasksScreen.kt`'s `colorForUrgency`, `ui/tasks/PriorityUi.kt`'s
 * `Priority.indicatorColor()`) and Glance (`ui/widget/WidgetColors.kt`'s `urgencyColorProvider`,
 * `Priority.glanceIndicatorColor()`). Both worlds used to repeat the same `when` — a mapping
 * duplicated across two files, kept in sync only by a KDoc warning. This feature (2026-08-17,
 * "widget-hilt-color-token") extracts the `when` into [urgencyColorRole]/[priorityColorRole] below,
 * so the mapping exists exactly once and each world is left with a thin resolver that only
 * translates role -> color using its own theme source (`MaterialTheme.colorScheme`/
 * `NeverLateExtras` for Compose, `GlanceTheme.colors` + the hand-written day/night pairs for
 * Glance — see that file's KDoc for why those pairs still exist and are not part of this
 * duplication).
 *
 * Placed in `domain/tasks/` — next to [Urgency.kt] — rather than `ui/theme/`, because the decision
 * this models ("what does urgency/priority *mean*", not "what hex value is `primary`") is
 * vocabulary of the tasks domain, the same reasoning already applied to [urgencyLevelFor]. It is
 * deliberately **not** in `ui/widget/`: the widget is the minor of the two consumers, and pinning a
 * shared type to the minor consumer's package would make the major consumer (the task list) depend
 * "upward" into widget code.
 */
enum class ColorRole { Calm, Soon, Error, Primary, Secondary, Tertiary }

/**
 * [UrgencyLevel.Urgent] and [UrgencyLevel.Overdue] share [ColorRole.Error] in both worlds today —
 * visually "urgent" and "error" are the same "look at this now" signal — so this maps **four**
 * urgency levels onto **three** roles, not four; that asymmetry is intentional and predates this
 * feature (see `colorForUrgency`'s original KDoc), not something introduced by extracting it.
 */
fun urgencyColorRole(level: UrgencyLevel): ColorRole = when (level) {
    UrgencyLevel.Calm -> ColorRole.Calm
    UrgencyLevel.Soon -> ColorRole.Soon
    UrgencyLevel.Urgent, UrgencyLevel.Overdue -> ColorRole.Error
}

/**
 * [Priority.NONE] has no indicator color at all — returns `null` so the caller draws nothing (no
 * visual noise for the default, US-2 of feature 13b). The remaining three priorities rise in
 * salience from [ColorRole.Secondary] ([Priority.LOW]) to [ColorRole.Primary] ([Priority.HIGH]).
 */
fun priorityColorRole(priority: Priority): ColorRole? = when (priority) {
    Priority.NONE -> null
    Priority.LOW -> ColorRole.Secondary
    Priority.MEDIUM -> ColorRole.Tertiary
    Priority.HIGH -> ColorRole.Primary
}
