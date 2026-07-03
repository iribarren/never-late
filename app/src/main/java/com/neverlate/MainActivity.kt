package com.neverlate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.neverlate.data.DataStoreUserPreferencesRepository
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.articles.ArticleRepository
import com.neverlate.data.articles.LocalArticleRepository
import com.neverlate.data.tasks.NeverLateDatabase
import com.neverlate.data.tasks.RoomTaskRepository
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.ui.navigation.AppNavHost
import com.neverlate.ui.notification.TasksNotificationService
import com.neverlate.ui.theme.NeverLateTheme
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
 * [TaskRepository] is wrapped in [TaskSurfacesRefreshingRepository] (features 05 + 06) so every
 * write made anywhere in the app also refreshes both read-only surfaces — the home-screen widget
 * and the lock-screen notification — see that class's KDoc for why neither can simply observe the
 * repository the way this app's `ViewModel`s do.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository: UserPreferencesRepository = DataStoreUserPreferencesRepository(applicationContext)
        val articleRepository: ArticleRepository = LocalArticleRepository(applicationContext)
        val database = NeverLateDatabase.getInstance(applicationContext)
        val taskRepository: TaskRepository =
            TaskSurfacesRefreshingRepository(RoomTaskRepository(database.taskDao()), applicationContext)

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
            NeverLateTheme {
                AppNavHost(
                    repository = repository,
                    articleRepository = articleRepository,
                    taskRepository = taskRepository,
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
