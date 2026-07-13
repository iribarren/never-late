package com.neverlate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neverlate.data.ThemeMode
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.articles.ArticleRepository
import com.neverlate.data.auth.AuthRepositoryImpl
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.ui.navigation.AppNavHost
import com.neverlate.ui.notification.ReminderNotificationHelper
import com.neverlate.ui.notification.TasksNotificationService
import com.neverlate.ui.theme.NeverLateTheme
import com.neverlate.ui.theme.themeModeToDark
import com.neverlate.ui.widget.TaskSurfacesRefreshWorker
import com.neverlate.data.sync.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single entry point of the app. In Compose there is usually one Activity that hosts the whole
 * UI tree declared inside [setContent].
 *
 * Feature 13d replaces this class's former manual dependency injection — a construction block
 * that used to build [UserPreferencesRepository], [com.neverlate.data.tasks.NeverLateDatabase],
 * the whole [TaskRepository] decorator chain, [AuthRepositoryImpl], [com.neverlate.data.sync.SyncEngine]
 * and friends inline, right here in [onCreate] — with **Hilt**: `@AndroidEntryPoint` below makes
 * this class a member of Hilt's generated `SingletonComponent`, so the three `@Inject`ed fields
 * are simply *handed to it*, already fully built, by the providers in `di/` (`DatabaseModule`,
 * `NetworkModule`, `StorageModule`, `RepositoryModule`). Every screen's `ViewModel` gets its own
 * repositories the same way, via `@HiltViewModel` + `hiltViewModel()` — see e.g.
 * [com.neverlate.ui.tasks.TasksViewModel]. This class now does exactly two things: wires the one
 * piece of state Hilt cannot express as a static dependency graph (the guest-mode adoption hook,
 * below), and runs the app's imperative startup side effects — nothing here *constructs* an
 * object anymore.
 *
 * [taskRepository] is the assembled, outermost [TaskRepository] — features 05+06's
 * [com.neverlate.ui.widget.TaskSurfacesRefreshingRepository] wrapping feature 09's
 * [com.neverlate.ui.notification.ReminderSchedulingRepository] wrapping feature 11's
 * [com.neverlate.data.sync.OutboxTaskRepository] wrapping the real, Room-backed
 * [com.neverlate.data.tasks.RoomTaskRepository] — composed by
 * [com.neverlate.di.RepositoryModule]'s qualified providers in that exact order (see its KDoc for
 * why the order is a behavioural contract, not just a construction detail). [authRepositoryImpl]
 * (feature 11) is injected as its **concrete** type, not the [com.neverlate.data.auth.AuthRepository]
 * interface every ViewModel sees, because only this class needs its `onAuthenticated` hook below —
 * see [AuthRepositoryImpl.onAuthenticated]'s KDoc.
 *
 * Feature 18b adds [articleRepository]: the expanded-width two-pane Articles screen
 * ([com.neverlate.ui.articles.ArticlesListDetailPane]) loads its selected article directly from
 * the repository instead of through a `hiltViewModel()`-obtained
 * [com.neverlate.ui.articles.ArticleDetailViewModel] — that ViewModel reads its `articleId` from a
 * navigation-backed `SavedStateHandle`, which fits a *pushed* route but not an in-pane selection
 * with no navigation event of its own (see that pane's KDoc).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    @Inject
    lateinit var taskRepository: TaskRepository

    @Inject
    lateinit var authRepositoryImpl: AuthRepositoryImpl

    @Inject
    lateinit var articleRepository: ArticleRepository

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Feature 13 (guest mode): belt-and-braces adoption trigger. taskRepository must exist
        // before this can be wired, which is why it is assigned here (both fields already
        // injected by the time onCreate runs) rather than passed into AuthRepositoryImpl's own
        // constructor — see AuthRepositoryImpl.onAuthenticated's KDoc for why this is redundant
        // insurance alongside MainAppNavHost's own LaunchedEffect, not the only mechanism.
        authRepositoryImpl.onAuthenticated = { taskRepository.refreshFromServer() }

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
            val userPreferences by userPreferencesRepository.userPreferences.collectAsStateWithLifecycle(initialValue = null)
            val themeMode = userPreferences?.themeMode ?: ThemeMode.SYSTEM
            val darkTheme = themeModeToDark(themeMode, isSystemInDarkTheme())
            // Same null-guard reasoning as themeMode above: while DataStore is still loading, fall
            // back to the brand-first default (false) rather than momentarily rendering Material
            // You (feature 16).
            val dynamicColor = userPreferences?.dynamicColor ?: false

            // Feature 18b: compact/medium/expanded, recalculated by the framework itself whenever
            // the window is resized/rotated (calculateWindowSizeClass is @Composable, unlike a
            // one-shot Activity-level read) — threaded down as a plain value, never re-derived
            // further down the tree, so every adaptive decision (bar vs rail, one vs two Articles
            // panes, the Tasks/Settings max-width constraint) agrees on the same reading.
            val windowSizeClass = calculateWindowSizeClass(this)

            NeverLateTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                AppNavHost(
                    authRepository = authRepositoryImpl,
                    repository = userPreferencesRepository,
                    taskRepository = taskRepository,
                    articleRepository = articleRepository,
                    openTasksOnStart = openTasksOnStart,
                    widthSizeClass = windowSizeClass.widthSizeClass,
                )
            }
        }
    }

    companion object {
        /** Intent extra the pending-tasks widget (feature 05) sets on its tap action. */
        const val EXTRA_OPEN_TASKS = "com.neverlate.EXTRA_OPEN_TASKS"
    }
}
