package com.neverlate.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.neverlate.MainActivity
import com.neverlate.R
import com.neverlate.data.tasks.NeverLateDatabase
import com.neverlate.data.tasks.RoomTaskRepository
import kotlinx.coroutines.flow.first

/** Background/text colors kept local and simple — see the feature spec's "no advanced theming". */
private val WidgetBackground = Color(0xFFEFE6FF)
private val WidgetTitleColor = ColorProvider(Color(0xFF4A3B77))
private val WidgetTextColor = ColorProvider(Color(0xFF1B1B1B))
private val WidgetTimedOutColor = ColorProvider(Color(0xFFB3261E))

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
            PendingTasksWidgetContent(model = model, context = context)
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
            .background(WidgetBackground)
            .padding(12.dp)
            .clickable(openTasks),
    ) {
        Text(
            text = context.getString(R.string.widget_pending_tasks_title),
            style = TextStyle(color = WidgetTitleColor, fontWeight = FontWeight.Bold),
        )
        when (model) {
            is PendingTasksWidgetModel.Empty -> {
                Text(
                    text = context.getString(R.string.widget_pending_tasks_empty),
                    style = TextStyle(color = WidgetTextColor),
                )
            }

            is PendingTasksWidgetModel.Content -> {
                for (row in model.rows) {
                    PendingTaskRowContent(row)
                }
            }
        }
    }
}

@Composable
private fun PendingTaskRowContent(row: PendingTaskRow) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = row.title,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = WidgetTextColor),
        )
        Text(
            text = row.remaining,
            // A timed-out task (remaining == 0) is called out in red rather than blending in with
            // the rest — it is the row most likely to need attention right now.
            style = TextStyle(
                color = if (row.isTimedOut) WidgetTimedOutColor else WidgetTitleColor,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}
