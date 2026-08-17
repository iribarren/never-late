package com.neverlate.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
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
import com.neverlate.data.tasks.NeverLateDatabase
import com.neverlate.data.tasks.Priority
import com.neverlate.data.tasks.RoomTaskRepository
import com.neverlate.domain.tasks.PendingTaskRow
import com.neverlate.domain.tasks.UrgencyLevel
import com.neverlate.domain.tasks.urgencyLevel
import com.neverlate.ui.components.formatRemainingLabel
import com.neverlate.ui.tasks.labelRes
import com.neverlate.ui.theme.DarkColorScheme
import com.neverlate.ui.theme.LightColorScheme
import kotlinx.coroutines.flow.first

/**
 * The home-screen "pending tasks" App Widget. Glance translates the composables built in
 * [provideGlance] into `RemoteViews`, which is what actually lets this draw inside the launcher's
 * process instead of the app's — see `tutorial/05-widget.md` for the full explanation.
 */
class PendingTasksWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // A widget cannot reuse MainActivity's manually-injected repository — it never runs
        // MainActivity.onCreate. Instead it reaches the same process-wide database singleton
        // directly from its own Context, exactly like MainActivity does, and builds the same
        // RoomTaskRepository on top of it. Same data, same types, no new data-access path.
        val database = NeverLateDatabase.getInstance(context)
        val repository = RoomTaskRepository(database.taskDao())

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
            .clickable(openTasks),
    ) {
        WidgetHeader(context)
        when (model) {
            is PendingTasksWidgetModel.Empty -> {
                Text(
                    text = context.getString(R.string.widget_pending_tasks_empty),
                    modifier = GlanceModifier.padding(16.dp),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                )
            }

            is PendingTasksWidgetModel.Content -> {
                model.rows.forEachIndexed { index, row ->
                    PendingTaskRowContent(row, context)
                    // A hairline divider between rows, not after the last one — the row separation
                    // US-5 asks for, replacing the previous 2dp-padding "block of text" look.
                    if (index != model.rows.lastIndex) {
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
private fun PendingTaskRowContent(row: PendingTaskRow, context: Context) {
    // Feature 20b: derived from the raw millis rather than a pre-baked flag — this is also the
    // fix for the bug where this row froze at "00:00" instead of showing "Tiempo agotado" like
    // the card and notification. Feature 05b: the same derivation, now named once in
    // PendingTaskRow.urgencyLevel() so the widget's countdown color and the app's colorForUrgency
    // agree on the same four-level scale (urgencyLevelFor).
    val level = row.urgencyLevel()
    val markerColor = row.priority.glanceIndicatorColor()
    val markerText = when (row.priority) {
        Priority.NONE -> null
        Priority.LOW -> context.getString(R.string.widget_priority_marker_low)
        Priority.MEDIUM -> context.getString(R.string.widget_priority_marker_medium)
        Priority.HIGH -> context.getString(R.string.widget_priority_marker_high)
    }
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
    val rowDescription = listOfNotNull(row.title, remainingLabel, priorityDescription).joinToString(separator = ", ")

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = rowDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            // US-3: urgency is never color-only — Urgent/Overdue also get bold weight, on top of
            // the four-level color from urgencyColorProvider (WidgetColors.kt).
            // The calm/soon baseline is Medium rather than Normal so US-5's "the countdown is
            // visually dominant" still holds at every level (the title is Normal), without
            // spending the Bold step, which belongs to the urgency channel alone.
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
}
