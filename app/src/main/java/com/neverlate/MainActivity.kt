package com.neverlate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neverlate.data.DataStoreUserPreferencesRepository
import com.neverlate.data.ThemeMode
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.articles.ArticleRepository
import com.neverlate.data.articles.LocalArticleRepository
import com.neverlate.data.tasks.NeverLateDatabase
import com.neverlate.data.tasks.RoomTaskRepository
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.ui.navigation.AppNavHost
import com.neverlate.ui.notification.AlarmManagerReminderScheduler
import com.neverlate.ui.notification.ReminderNotificationHelper
import com.neverlate.ui.notification.ReminderScheduler
import com.neverlate.ui.notification.ReminderSchedulingRepository
import com.neverlate.ui.notification.TasksNotificationService
import com.neverlate.ui.theme.NeverLateTheme
import com.neverlate.ui.theme.themeModeToDark
import com.neverlate.ui.widget.TaskSurfacesRefreshWorker
import com.neverlate.ui.widget.TaskSurfacesRefreshingRepository

/**
 * Single entry point of the app. In Compose there is usually one Activity that hosts the whole
 * UI tree declared inside [setContent].
 *
 * The [UserPreferencesRepository], [ArticleRepository] and [TaskRepository] are created once
 * here (manual dependency injection — no framework needed for a project this size) and threaded
 * down into [AppNavHost], which passes each to whichever screen needs it via
 * [com.neverlate.ui.navigation.AppViewModelFactory]. [TaskRepository] is backed by
 * [NeverLateDatabase], this project's first Room database — [NeverLateDatabase.getInstance]
 * takes care of creating (or reusing) the single instance for the whole process.
 *
 * [TaskRepository] is wrapped in two decorators, composed in the order they should run: features
 * 05 + 06's [TaskSurfacesRefreshingRepository] (refreshes the widget/lock-screen summary) wraps
 * feature 09's [ReminderSchedulingRepository] (schedules/cancels each task's alarm), which in turn
 * wraps the real, Room-backed [RoomTaskRepository]. Every write made anywhere in the app therefore
 * both refreshes those read-only surfaces and keeps reminders in sync — see each decorator's KDoc
 * for why neither can simply observe the repository the way this app's `ViewModel`s do.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository: UserPreferencesRepository = DataStoreUserPreferencesRepository(applicationContext)
        val articleRepository: ArticleRepository = LocalArticleRepository(applicationContext)
        val database = NeverLateDatabase.getInstance(applicationContext)
        val reminderScheduler: ReminderScheduler = AlarmManagerReminderScheduler(applicationContext)
        val taskRepository: TaskRepository = TaskSurfacesRefreshingRepository(
            ReminderSchedulingRepository(RoomTaskRepository(database.taskDao()), reminderScheduler, repository),
            applicationContext,
        )

        // The reminders channel must exist before ReminderReceiver can ever post to it; creating
        // it here (in addition to defensively inside that receiver) means it is ready the moment
        // the very first task with a deadline is saved.
        ReminderNotificationHelper.ensureChannel(applicationContext)

        // Enqueued on every app start, but ExistingPeriodicWorkPolicy.KEEP (inside
        // enqueuePeriodic) makes this idempotent, so it only ever actually schedules once.
        TaskSurfacesRefreshWorker.enqueuePeriodic(applicationContext)

        // Evaluate the lock-screen notification right away on launch, so it appears (or is cleared)
        // immediately based on the current tasks, without waiting for the next write or the ~15-min
        // periodic worker. The service reads a fresh snapshot and tears itself down if nothing is
        // pending or notifications are disabled, so calling this unconditionally is safe.
        TasksNotificationService.refresh(applicationContext)

        // Set by PendingTasksWidget's tap action (see its KDoc) so the app opens straight on the
        // tasks list instead of wherever it would normally start.
        val openTasksOnStart = intent?.getBooleanExtra(EXTRA_OPEN_TASKS, false) ?: false

        setContent {
            // Read the persisted theme preference at the very top of the composition, so it drives
            // colours for the whole app (not just the Settings screen). While DataStore is still
            // loading from disk the value is null; we fall back to SYSTEM to avoid a flash of the
            // wrong theme, the same "startup flash" reasoning AppNavHost uses for its start route.
            val userPreferences by repository.userPreferences.collectAsStateWithLifecycle(initialValue = null)
            val themeMode = userPreferences?.themeMode ?: ThemeMode.SYSTEM
            val darkTheme = themeModeToDark(themeMode, isSystemInDarkTheme())

            NeverLateTheme(darkTheme = darkTheme) {
                AppNavHost(
                    repository = repository,
                    articleRepository = articleRepository,
                    taskRepository = taskRepository,
                    reminderScheduler = reminderScheduler,
                    openTasksOnStart = openTasksOnStart,
                )
            }
        }
    }

    companion object {
        /** Intent extra the pending-tasks widget (feature 05) sets on its tap action. */
        const val EXTRA_OPEN_TASKS = "com.neverlate.EXTRA_OPEN_TASKS"
    }
}
