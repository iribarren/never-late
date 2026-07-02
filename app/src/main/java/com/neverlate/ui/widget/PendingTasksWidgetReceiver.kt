package com.neverlate.ui.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * System entry point for the widget. Android's `AppWidgetManager` talks to this
 * `BroadcastReceiver` (declared in `AndroidManifest.xml`), and `GlanceAppWidgetReceiver` forwards
 * every relevant broadcast (placed, resized, update requested, removed) to [glanceAppWidget].
 * Application code — including [TaskSurfacesRefreshingRepository] and [TaskSurfacesRefreshWorker] — never
 * talks to this class directly; it calls `PendingTasksWidget().updateAll(context)`, and Glance
 * routes the resulting `RemoteViews` update through the system back to here.
 */
class PendingTasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PendingTasksWidget()
}
