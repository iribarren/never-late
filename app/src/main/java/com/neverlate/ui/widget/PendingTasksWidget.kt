package com.neverlate.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.neverlate.MainActivity
import com.neverlate.R
import com.neverlate.data.tasks.Priority
import com.neverlate.di.WidgetEntryPoint
import com.neverlate.domain.tasks.PendingTaskRow
import com.neverlate.domain.tasks.UrgencyLevel
import com.neverlate.domain.tasks.deadlineProgressFor
import com.neverlate.domain.tasks.urgencyLevel
import com.neverlate.ui.components.formatRemainingLabel
import com.neverlate.ui.tasks.labelRes
import com.neverlate.ui.tasks.markerRes
import com.neverlate.ui.theme.DarkColorScheme
import com.neverlate.ui.theme.LightColorScheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

/**
 * The two size buckets [PendingTasksWidget] declares (spec `widget-adaptive-layout`, D1). Both
 * share the same 250dp width — the axis that distinguishes them is height — so
 * [PendingTasksWidgetContent] can decide "small or large" purely from which bucket
 * [LocalSize.current] resolves to. [SMALL_WIDGET] matches `pending_tasks_widget_info.xml`'s
 * existing `minWidth`/`minHeight`, so the small bucket is exactly today's layout; [LARGE_WIDGET]
 * is roughly a 4x4-cell placement. Deliberately only two buckets: `SizeMode.Responsive`
 * pre-renders one full `RemoteViews` tree per declared size, so each extra bucket is real weight
 * in the update transaction — two is the minimum that answers the one question this feature
 * needs ("does a progress bar and 48dp rows fit, or not?").
 */
private val SMALL_WIDGET = DpSize(250.dp, 110.dp)
private val LARGE_WIDGET = DpSize(250.dp, 220.dp)

/**
 * The home-screen "pending tasks" App Widget. Glance translates the composables built in
 * [provideGlance] into `RemoteViews`, which is what actually lets this draw inside the launcher's
 * process instead of the app's — see `tutorial/05-widget.md` for the full explanation.
 */
class PendingTasksWidget : GlanceAppWidget() {

    // D1: pre-renders one RemoteViews tree per declared size; the launcher then picks whichever
    // is closest to the space it actually has, and LocalSize.current inside the composition
    // resolves to that exact declared DpSize (not the launcher's raw pixel size) — see
    // PendingTasksWidgetContent for how that value picks small vs. large layout.
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL_WIDGET, LARGE_WIDGET))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // A widget cannot reuse MainActivity's @Inject-ed repository — it never runs
        // MainActivity.onCreate, and Hilt never constructs a GlanceAppWidget in the first place
        // (see WidgetEntryPoint's KDoc for the full why). EntryPointAccessors.fromApplication
        // reaches the same Hilt-managed singleton graph MainActivity gets, from the widget's own
        // Context — resolved here, inside provideGlance, so the three call sites that construct
        // PendingTasksWidget() don't need to change at all.
        val repository = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            .taskRepository()

        // `.first()` takes a one-shot snapshot instead of an ongoing subscription: a widget
        // draws on demand (once per provideGlance call) rather than observing continuously like
        // TasksViewModel does, so there is nothing to keep collecting after this point.
        val tasks = repository.observeTasks().first()
        val model = toWidgetModel(tasks, System.currentTimeMillis())

        provideContent {
            // Feature 05b (D1): wraps the widget in the app's OWN LightColorScheme/DarkColorScheme
            // (Theme.kt, made internal for exactly this) via glance-material3's ColorProviders
            // bridge, instead of GlanceTheme() with no arguments (which would use Material You /
            // dynamic color). The widget always uses the brand palette, matching the app's
            // default (dynamicColor = false, see NeverLateTheme) — see WidgetColors.kt's KDoc for
            // why the two themes cannot instead simply share one CompositionLocal.
            GlanceTheme(colors = ColorProviders(light = LightColorScheme, dark = DarkColorScheme)) {
                PendingTasksWidgetContent(model = model, context = context)
            }
        }
    }
}

@Composable
private fun PendingTasksWidgetContent(model: PendingTasksWidgetModel, context: Context) {
    // Tapping anywhere on the widget opens MainActivity straight on the tasks list. Glance builds
    // the PendingIntent for us (with FLAG_IMMUTABLE, as current Android versions require) — we
    // only need to describe which Activity and Intent to launch.
    val openTasks = actionStartActivity(
        Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_TASKS, true)
        },
    )

    // D1: which of the two declared buckets this composition is being drawn for. Comparing
    // against LARGE_WIDGET rather than SMALL_WIDGET is deliberate: it treats "large" as the one
    // explicit bucket to opt into, so any future third bucket added above LARGE_WIDGET still
    // falls into the large layout rather than silently landing in the small one.
    val isLargeBucket = LocalSize.current == LARGE_WIDGET

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            // D4: one drawable path on every API level (24-36) — the shape (rounded rect, see
            // widget_background.xml) comes from the drawable, the color from the theme (D1), so
            // there is no hex value in resource XML and no cornerRadius()/SDK_INT branch.
            .background(
                ImageProvider(R.drawable.widget_background),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.background),
            )
            // US-3: in the large bucket, each row below carries its own actionRunCallback and
            // consumes its own taps — this root click only ever fires for the header band and any
            // remaining chrome (or, in the small bucket, the rows themselves too, unchanged).
            .clickable(openTasks),
    ) {
        WidgetHeader(context)
        when (model) {
            is PendingTasksWidgetModel.Empty -> {
                // V-8: identical empty state in both buckets — no bucket-specific branching here.
                Text(
                    text = context.getString(R.string.widget_pending_tasks_empty),
                    modifier = GlanceModifier.padding(16.dp),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                )
            }

            is PendingTasksWidgetModel.Content -> {
                val rows = rowsForBucket(model.rows, isLargeBucket)
                rows.forEachIndexed { index, row ->
                    PendingTaskRowContent(row, context, isLargeBucket)
                    // A hairline divider between rows, not after the last one — the row separation
                    // US-5 asks for, replacing the previous 2dp-padding "block of text" look.
                    if (index != rows.lastIndex) {
                        Box(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(1.dp)
                                .background(dividerColor),
                        ) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetHeader(context: Context) {
    // US-1: the widget's title on a brand-container band — the same brand-chrome idiom feature 20
    // gave the app's top app bars, translated for Glance (a shape drawable + theme tint, D4).
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(
                ImageProvider(R.drawable.widget_header_background),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primaryContainer),
            )
            .padding(12.dp),
    ) {
        Text(
            text = context.getString(R.string.widget_pending_tasks_title),
            style = TextStyle(
                color = GlanceTheme.colors.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            ),
        )
    }
}

@Composable
private fun PendingTaskRowContent(row: PendingTaskRow, context: Context, isLargeBucket: Boolean) {
    // Feature 20b: derived from the raw millis rather than a pre-baked flag — this is also the
    // fix for the bug where this row froze at "00:00" instead of showing "Tiempo agotado" like
    // the card and notification. Feature 05b: the same derivation, now named once in
    // PendingTaskRow.urgencyLevel() so the widget's countdown color and the app's colorForUrgency
    // agree on the same four-level scale (urgencyLevelFor).
    val level = row.urgencyLevel()
    val isTimedOut = row.remainingMillis == 0L
    val markerColor = row.priority.glanceIndicatorColor()
    // D8 (`priority-sorting`): the widget no longer decides its own marker text — it resolves the
    // one shared mapping (ui/tasks/PriorityUi.kt) also used by the task card and the notification.
    val markerText = row.priority.markerRes()?.let { context.getString(it) }
    val remainingLabel = formatRemainingLabel(context, row.remainingMillis)

    // Glance's semantics apply per-node, not merged up an accessibility tree the way Compose's
    // are (there is no separate "priority dot" node to hang a description off, unlike the task
    // card) — so the row itself carries one description that names the title, the remaining time
    // and, for a non-NONE priority, the same tasks_priority_content_description wording the task
    // card uses ("Prioridad: Alta"), so TalkBack announces priority in words (US-4).
    val priorityDescription = if (row.priority != Priority.NONE) {
        context.getString(R.string.tasks_priority_content_description, context.getString(row.priority.labelRes()))
    } else {
        null
    }
    val baseDescription = listOfNotNull(row.title, remainingLabel, priorityDescription).joinToString(separator = ", ")
    // US-4: the large bucket's row is itself a tap target that completes the task, so its
    // description names the action ("Completar: ..."), not just the data — the small bucket keeps
    // the plain, verb-free description because there it is only ever read out, never tapped alone.
    val rowDescription = if (isLargeBucket) {
        context.getString(R.string.widget_row_complete_content_description, baseDescription)
    } else {
        baseDescription
    }

    // US-2/V-1: only the large bucket, and only when the task has a usable duration window, gets
    // a bar at all — DeadlineProgress.kt (read-only here) is the only source of the fraction.
    val progress = if (isLargeBucket) deadlineProgressFor(row.remainingMillis, row.totalMillis, isTimedOut) else null

    var rowModifier = GlanceModifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .semantics { contentDescription = rowDescription }
    if (isLargeBucket) {
        // V-5: guarantees a >= 48dp tall tap target for the per-row complete action; the small
        // bucket keeps its compact, non-tappable row height instead.
        rowModifier = rowModifier.height(48.dp)
        // US-3: actionRunCallback runs CompleteTaskActionCallback in the launcher process,
        // passing this row's task id — see that class for the write path and D4's reentrancy fix.
        rowModifier = rowModifier.clickable(
            actionRunCallback<CompleteTaskActionCallback>(actionParametersOf(taskIdKey to row.id)),
        )
    }

    Column(modifier = rowModifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // US-4: Priority.NONE shows no marker at all — same "no visual noise for the default"
            // rule as the task card's priority dot.
            if (markerText != null && markerColor != null) {
                Text(
                    text = markerText,
                    modifier = GlanceModifier.padding(end = 8.dp),
                    style = TextStyle(color = markerColor, fontWeight = FontWeight.Bold, fontSize = 14.sp),
                )
            }
            Text(
                text = row.title,
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
            )
            Text(
                text = remainingLabel,
                // US-3: urgency is never color-only — Urgent/Overdue also get bold weight, on top
                // of the four-level color from urgencyColorProvider (WidgetColors.kt).
                // The calm/soon baseline is Medium rather than Normal so US-5's "the countdown is
                // visually dominant" still holds at every level (the title is Normal), without
                // spending the Bold step, which belongs to the urgency channel alone.
                // D5/V-7: on API 24-30 the bar below cannot carry the urgency color (platform
                // limit), so this color + weight pair stays the primary urgency signal there too.
                style = TextStyle(
                    color = urgencyColorProvider(level),
                    fontWeight = if (level == UrgencyLevel.Urgent || level == UrgencyLevel.Overdue) {
                        FontWeight.Bold
                    } else {
                        FontWeight.Medium
                    },
                    fontSize = 14.sp,
                ),
            )
        }
        // V-3/V-4: the bar lives below the text line, full row width, 4dp tall with 4dp of
        // separation from the text above — never inside the text line, never pushing it around.
        if (progress != null) {
            LinearProgressIndicator(
                progress = progress,
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(4.dp),
                color = urgencyColorProvider(level),
                backgroundColor = GlanceTheme.colors.surfaceVariant,
            )
        }
    }
}
