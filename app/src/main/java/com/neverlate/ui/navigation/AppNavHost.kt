package com.neverlate.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.articles.ArticleRepository
import com.neverlate.data.auth.AuthRepository
import com.neverlate.data.auth.AuthState
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.ui.articles.ArticleDetailRoute
import com.neverlate.ui.articles.ArticlesRoute
import com.neverlate.ui.auth.LoginRoute
import com.neverlate.ui.auth.RegisterRoute
import com.neverlate.ui.home.HomeRoute
import com.neverlate.ui.notification.ReminderScheduler
import com.neverlate.ui.onboarding.OnboardingRoute
import com.neverlate.ui.settings.SettingsRoute
import com.neverlate.ui.tasks.TaskEditRoute
import com.neverlate.ui.tasks.TasksRoute

/** Destination names for the nav graph, kept as constants so routes can't be mistyped. */
private object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ARTICLES = "articles"
    const val ARTICLE_DETAIL = "articleDetail"
    const val TASKS = "tasks"
    const val TASK_EDIT = "taskEdit"
    const val SETTINGS = "settings"
}

/** Name of the navigation argument carrying an [com.neverlate.data.articles.Article.id]. */
private const val ARG_ARTICLE_ID = "articleId"

/** Name of the navigation argument carrying a [com.neverlate.data.tasks.Task.id]. */
private const val ARG_TASK_ID = "taskId"

/**
 * App-wide navigation graph (Navigation Compose). Feature 11 added an **auth gate** on top of
 * everything this composable already did, observing [authRepository]'s `authState`; feature 13
 * (guest mode) turns that gate into a three-way switch: [AuthState.LoggedOut] shows
 * [AuthGateNavHost] (login/register, the only *mandatory* case now — an involuntarily-ended
 * session), while both [AuthState.Guest] and [AuthState.LoggedIn] show [MainAppNavHost] — a
 * signed-out device is fully usable, not gated.
 *
 * **Why `Guest` and `LoggedIn` are separate `when` branches rather than one combined arm:** this
 * is deliberate, not an oversight (feature 13 spec, *Risks* — "adoption not firing"). Compose
 * identifies composition state by *call site*, not by which function is called, so a `Guest ->
 * LoggedIn` transition switches which branch of this `when` is active, which disposes the old
 * [MainAppNavHost] instance and composes a brand-new one at the other branch's call site — fresh
 * `rememberNavController()`, fresh `LaunchedEffect(Unit)`. That freshly-fired `LaunchedEffect` is
 * exactly what drains a guest's queued outbox on sign-in (see [MainAppNavHost]'s KDoc) — merging
 * the two arms into one (e.g. `is AuthState.Guest, is AuthState.LoggedIn ->`) would keep the
 * *same* composition alive across the transition and silently break that trigger. (Belt-and-braces:
 * [com.neverlate.data.auth.AuthRepositoryImpl.onAuthenticated] triggers the same drain explicitly
 * too, so adoption does not depend solely on this recomposition detail — see its KDoc.)
 *
 * Every branch/transition (successful login/register/logout on either side) simply swaps which
 * graph is composed, with no explicit navigation call needed from
 * [com.neverlate.ui.auth.LoginViewModel]/[com.neverlate.ui.auth.RegisterViewModel]/
 * [com.neverlate.ui.settings.SettingsViewModel] (see each one's KDoc). Each branch keeps its own
 * `rememberNavController()`, so switching branches naturally starts with a fresh back stack
 * instead of carrying over stale routes from the other graph.
 */
@Composable
fun AppNavHost(
    authRepository: AuthRepository,
    repository: UserPreferencesRepository,
    articleRepository: ArticleRepository,
    taskRepository: TaskRepository,
    reminderScheduler: ReminderScheduler,
    openTasksOnStart: Boolean = false,
) {
    val authState by authRepository.authState.collectAsStateWithLifecycle()

    when (authState) {
        is AuthState.LoggedOut -> AuthGateNavHost(authRepository = authRepository)

        is AuthState.Guest -> MainAppNavHost(
            repository = repository,
            articleRepository = articleRepository,
            taskRepository = taskRepository,
            reminderScheduler = reminderScheduler,
            authRepository = authRepository,
            openTasksOnStart = openTasksOnStart,
        )

        is AuthState.LoggedIn -> MainAppNavHost(
            repository = repository,
            articleRepository = articleRepository,
            taskRepository = taskRepository,
            reminderScheduler = reminderScheduler,
            authRepository = authRepository,
            openTasksOnStart = openTasksOnStart,
        )
    }
}

/**
 * Login/register graph, shown only while [AuthState.LoggedOut] — an *involuntary* session end
 * (feature 12's failed silent refresh). A [AuthState.Guest] user reaches the very same
 * [LoginRoute]/[RegisterRoute] composables from within [MainAppNavHost] instead (Settings ->
 * "Sign in / Create account"), since for them signing in is optional, not a gate to pass.
 */
@Composable
private fun AuthGateNavHost(authRepository: AuthRepository, navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginRoute(
                authRepository = authRepository,
                onRegisterClick = { navController.navigate(Routes.REGISTER) },
            )
        }
        composable(Routes.REGISTER) {
            RegisterRoute(
                authRepository = authRepository,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * The graph that existed before feature 11 — now reached from **both** [AuthState.Guest] and
 * [AuthState.LoggedIn] (feature 13 lifted the mandatory gate) — plus it also owns *startup
 * routing*: reading the persisted `onboarded` flag once to decide whether the user should land on
 * Onboarding or Home.
 *
 * Reading that flag is asynchronous (it comes from disk via DataStore), so
 * [repository.userPreferences][UserPreferencesRepository.userPreferences] is collected with an
 * `initialValue` of `null`. While it is `null` we show a neutral loading indicator instead of
 * guessing a start destination — otherwise a returning user could see a flash of Onboarding
 * before the real value arrives (see the feature spec's "startup flash" risk).
 *
 * [openTasksOnStart] is a second, narrower override of that same start destination: it is set
 * when `MainActivity` was opened by tapping the pending-tasks widget (feature 05), and sends an
 * already-onboarded user straight to [Routes.TASKS] instead of [Routes.HOME]. It is ignored for a
 * user who has not onboarded yet — Onboarding always wins, so tapping the widget can never skip
 * it.
 *
 * Feature 13 adds [Routes.LOGIN]/[Routes.REGISTER] as ordinary destinations *inside* this graph
 * (reachable from Settings' "Sign in / Create account" entry), distinct from [AuthGateNavHost]'s
 * copies of the same [LoginRoute]/[RegisterRoute] composables. A guest who cancels out of either
 * screen (back arrow) simply pops back to Settings within this same back stack — they are never
 * routed to the mandatory gate, since [AuthState.Guest] never renders [AuthGateNavHost] at all.
 */
@Composable
private fun MainAppNavHost(
    repository: UserPreferencesRepository,
    articleRepository: ArticleRepository,
    taskRepository: TaskRepository,
    reminderScheduler: ReminderScheduler,
    authRepository: AuthRepository,
    navController: NavHostController = rememberNavController(),
    openTasksOnStart: Boolean = false,
) {
    // Sync trigger (US-4) and, since feature 13, the primary adoption drain (US-2/US-3): app
    // open/foreground. This composable is reached for both AuthState.Guest and AuthState.LoggedIn
    // (see AppNavHost above), and — critically for adoption — is freshly composed at a *new* call
    // site every time a Guest signs in, since that transition switches which `when` branch is
    // active there. A single LaunchedEffect(Unit) here therefore covers cold start (existing
    // session or none), post-login/register, and app foregrounding alike. taskRepository is the
    // only sync-shaped thing this screen touches, via the additive TaskRepository.refreshFromServer
    // (US-7) — never SyncEngine/Retrofit directly. While AuthState.Guest, refreshFromServer's
    // underlying SyncEngine.syncNow() early-returns Idle (no token) — this call is simply a no-op
    // then, not a special case to guard against here.
    LaunchedEffect(Unit) { taskRepository.refreshFromServer() }

    val userPreferences by repository.userPreferences.collectAsStateWithLifecycle(initialValue = null)

    when (val preferences = userPreferences) {
        null -> LoadingIndicator()
        else -> {
            val startDestination = when {
                !preferences.onboarded -> Routes.ONBOARDING
                openTasksOnStart -> Routes.TASKS
                else -> Routes.HOME
            }

            NavHost(navController = navController, startDestination = startDestination) {
                composable(Routes.ONBOARDING) {
                    OnboardingRoute(
                        repository = repository,
                        onSaved = {
                            navController.navigate(Routes.HOME) {
                                // Pop Onboarding off the back stack: after saving, system back
                                // from Home must not return the user to Onboarding.
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Routes.HOME) {
                    HomeRoute(
                        repository = repository,
                        onArticlesClick = { navController.navigate(Routes.ARTICLES) },
                        onTasksClick = { navController.navigate(Routes.TASKS) },
                        onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsRoute(
                        repository = repository,
                        taskRepository = taskRepository,
                        reminderScheduler = reminderScheduler,
                        authRepository = authRepository,
                        onBack = { navController.popBackStack() },
                        onSignInClick = { navController.navigate(Routes.LOGIN) },
                    )
                }
                // Feature 13: login/register reachable from Settings while AuthState.Guest — see
                // this function's KDoc for how this differs from AuthGateNavHost's copies of the
                // same routes. A successful sign-in flips authState to LoggedIn, which AppNavHost
                // reacts to by composing a brand-new MainAppNavHost at a different call site (its
                // own KDoc), so neither destination below needs to navigate anywhere on success.
                composable(Routes.LOGIN) {
                    LoginRoute(
                        authRepository = authRepository,
                        onRegisterClick = { navController.navigate(Routes.REGISTER) },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.REGISTER) {
                    RegisterRoute(
                        authRepository = authRepository,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.ARTICLES) {
                    ArticlesRoute(
                        articleRepository = articleRepository,
                        onArticleClick = { articleId ->
                            navController.navigate("${Routes.ARTICLE_DETAIL}/$articleId")
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = "${Routes.ARTICLE_DETAIL}/{$ARG_ARTICLE_ID}",
                    arguments = listOf(navArgument(ARG_ARTICLE_ID) { type = NavType.StringType }),
                ) { backStackEntry ->
                    // Only the id crosses the navigation boundary — never the full Article — so
                    // the detail screen reloads the article from the repository by id. This
                    // keeps the route argument small and matches the "no complex objects in
                    // routes" constraint from the feature spec.
                    val articleId = backStackEntry.arguments?.getString(ARG_ARTICLE_ID).orEmpty()
                    ArticleDetailRoute(
                        articleRepository = articleRepository,
                        articleId = articleId,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.TASKS) {
                    TasksRoute(
                        taskRepository = taskRepository,
                        onAddTaskClick = { navController.navigate(Routes.TASK_EDIT) },
                        onTaskClick = { taskId -> navController.navigate("${Routes.TASK_EDIT}/$taskId") },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.TASK_EDIT) {
                    // No {taskId} argument on this route: TaskEditRoute below gets a null
                    // taskId, which is exactly what tells TaskEditViewModel to create a new task
                    // instead of loading an existing one.
                    TaskEditRoute(
                        taskRepository = taskRepository,
                        taskId = null,
                        onSaved = { navController.popBackStack() },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = "${Routes.TASK_EDIT}/{$ARG_TASK_ID}",
                    arguments = listOf(navArgument(ARG_TASK_ID) { type = NavType.LongType }),
                ) { backStackEntry ->
                    // Only the id crosses the navigation boundary — never the full Task — so the
                    // edit screen reloads the task from the repository by id, same reasoning as
                    // the article detail route above.
                    val taskId = backStackEntry.arguments?.getLong(ARG_TASK_ID)
                    TaskEditRoute(
                        taskRepository = taskRepository,
                        taskId = taskId,
                        onSaved = { navController.popBackStack() },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
