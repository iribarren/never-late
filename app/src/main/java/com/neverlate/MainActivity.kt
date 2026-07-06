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
import com.neverlate.data.articles.ArticlesNetwork
import com.neverlate.data.articles.CachingArticleRepository
import com.neverlate.data.auth.AuthNetwork
import com.neverlate.data.auth.AuthRepository
import com.neverlate.data.auth.AuthRepositoryImpl
import com.neverlate.data.auth.EncryptedTokenStorage
import com.neverlate.data.sync.OutboxTaskRepository
import com.neverlate.data.sync.SyncEngine
import com.neverlate.data.sync.SyncWorker
import com.neverlate.data.sync.TasksNetwork
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
 * [com.neverlate.ui.navigation.AppViewModelFactory]. [TaskRepository] and, since feature 10,
 * [ArticleRepository] are both backed by [NeverLateDatabase] — [NeverLateDatabase.getInstance]
 * takes care of creating (or reusing) the single instance for the whole process.
 * [ArticleRepository]'s concrete implementation, [CachingArticleRepository], additionally needs a
 * network client ([ArticlesNetwork.create]) to refresh that cache from the remote articles API.
 *
 * [TaskRepository] is wrapped in three decorators, composed in the order they should run: features
 * 05 + 06's [TaskSurfacesRefreshingRepository] (refreshes the widget/lock-screen summary) wraps
 * feature 09's [ReminderSchedulingRepository] (schedules/cancels each task's alarm), which wraps
 * feature 11's [OutboxTaskRepository] (stamps sync metadata and enqueues an outbox row for every
 * write), which in turn wraps the real, Room-backed [RoomTaskRepository]. Every write made
 * anywhere in the app therefore refreshes those read-only surfaces, keeps reminders in sync, *and*
 * queues itself for the backend — see each decorator's KDoc for why neither can simply observe
 * the repository the way this app's `ViewModel`s do.
 *
 * Feature 11 also adds an account layer *above* [TaskRepository]: [AuthRepository] (backed by
 * [EncryptedTokenStorage] for the JWT) gates the whole nav graph in [AppNavHost] — see that
 * function's KDoc — and [SyncEngine] is the thing [OutboxTaskRepository] actually talks to for
 * push/pull, built here with a [com.neverlate.data.sync.TasksApi] whose
 * [com.neverlate.data.sync.AuthInterceptor] attaches the session token and routes a `401` back to
 * [AuthRepository.notifyUnauthorized].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository: UserPreferencesRepository = DataStoreUserPreferencesRepository(applicationContext)
        val database = NeverLateDatabase.getInstance(applicationContext)
        val articleRepository: ArticleRepository =
            CachingArticleRepository(ArticlesNetwork.create(), database.articleDao())
        val reminderScheduler: ReminderScheduler = AlarmManagerReminderScheduler(applicationContext)

        // Auth (feature 11): the token storage and AuthRepository are built first, since the
        // tasks network client below needs a way to attach the current token and to report a 401
        // back — see AuthRepositoryImpl.notifyUnauthorized.
        val tokenStorage = EncryptedTokenStorage(applicationContext)
        val authRepositoryImpl = AuthRepositoryImpl(AuthNetwork.create(), tokenStorage, database, repository)
        val authRepository: AuthRepository = authRepositoryImpl

        val tasksApi = TasksNetwork.create(tokenStorage = tokenStorage, onUnauthorized = authRepositoryImpl::notifyUnauthorized)
        val syncEngine = SyncEngine(tasksApi, database, repository, tokenStorage)

        val taskRepository: TaskRepository = TaskSurfacesRefreshingRepository(
            ReminderSchedulingRepository(
                OutboxTaskRepository(database, RoomTaskRepository(database.taskDao()), syncEngine),
                reminderScheduler,
                repository,
            ),
            applicationContext,
        )

        // The reminders channel must exist before ReminderReceiver can ever post to it; creating
        // it here (in addition to defensively inside that receiver) means it is ready the moment
        // the very first task with a deadline is saved.
        ReminderNotificationHelper.ensureChannel(applicationContext)

        // Enqueued on every app start, but ExistingPeriodicWorkPolicy.KEEP (inside
        // enqueuePeriodic) makes this idempotent, so it only ever actually schedules once.
        TaskSurfacesRefreshWorker.enqueuePeriodic(applicationContext)

        // Feature 11: drains the outbox (and pulls remote changes) once connectivity returns,
        // even if the app is not in the foreground — the WorkManager backstop described in
        // SyncEngine's KDoc. Same enqueuePeriodic-on-every-start + KEEP idempotency as the worker
        // above.
        SyncWorker.enqueuePeriodic(applicationContext)

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
                    authRepository = authRepository,
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
