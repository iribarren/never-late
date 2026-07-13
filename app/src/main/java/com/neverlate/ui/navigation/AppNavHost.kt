package com.neverlate.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.neverlate.R
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.articles.ArticleRepository
import com.neverlate.data.auth.AuthRepository
import com.neverlate.data.auth.AuthState
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.ui.articles.ArticleDetailRoute
import com.neverlate.ui.articles.ArticlesListDetailPane
import com.neverlate.ui.articles.ArticlesRoute
import com.neverlate.ui.auth.LoginRoute
import com.neverlate.ui.auth.RegisterRoute
import com.neverlate.ui.components.ReadableWidthContainer
import com.neverlate.ui.onboarding.OnboardingRoute
import com.neverlate.ui.settings.SettingsRoute
import com.neverlate.ui.stats.StatsRoute
import com.neverlate.ui.tasks.TASK_CREATED_RESULT_KEY
import com.neverlate.ui.tasks.TaskEditRoute
import com.neverlate.ui.tasks.TasksRoute
import com.neverlate.ui.theme.NeverLateTheme

/** Destination names for the nav graph, kept as constants so routes can't be mistyped. */
private object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val ONBOARDING = "onboarding"
    const val ARTICLES = "articles"
    const val ARTICLE_DETAIL = "articleDetail"
    const val TASKS = "tasks"
    const val TASK_EDIT = "taskEdit"
    const val SETTINGS = "settings"
    const val STATS = "stats"
}

/**
 * Feature 18: the three top-level sections a persistent bottom [NavigationBar] switches between
 * laterally, as opposed to the push-style `navigate()` used for every other destination in this
 * graph (Article Detail, Task Edit, Login/Register). The bar is shown only while the current route
 * is one of these three — see [MainAppNavHost]'s `bottomBar` slot below.
 */
private val TOP_LEVEL_ROUTES = setOf(Routes.TASKS, Routes.ARTICLES, Routes.SETTINGS)

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
 *
 * Feature 13d: every `*ViewModel` in this graph now obtains its repositories via `hiltViewModel()`
 * (Hilt resolves them from `di/`), so this function itself only keeps the dependencies it uses
 * **directly**, never merely to thread through to a `*Route` for building a ViewModel by hand:
 * [authRepository] (`authState`, above), [repository] (the `onboarded` flag that decides
 * [MainAppNavHost]'s start destination), [taskRepository] (the sync-refresh `LaunchedEffect(Unit)`
 * in [MainAppNavHost]), and, since feature 18b, [articleRepository] (the expanded-width two-pane
 * Articles screen's direct `getArticleById` load — see [MainAppNavHost]'s KDoc). All four are
 * `@Inject`ed in [com.neverlate.MainActivity] and passed in here — a plain `@Composable` function
 * like this one cannot call `hiltViewModel()` itself (that only resolves `ViewModel`s), so this is
 * the simplest way to reach a Hilt-provided singleton from outside a `ViewModel`.
 *
 * [widthSizeClass] (feature 18b) is computed once in `MainActivity` from `calculateWindowSizeClass`
 * and threaded straight through, unexamined, to [MainAppNavHost] — the auth gate branch below
 * ([AuthGateNavHost]) deliberately ignores it: the login/register gate is full-height chrome-free
 * at every width (US-3 of the adaptive-layouts spec), so it has nothing to adapt.
 */
@Composable
fun AppNavHost(
    authRepository: AuthRepository,
    repository: UserPreferencesRepository,
    articleRepository: ArticleRepository,
    taskRepository: TaskRepository,
    openTasksOnStart: Boolean = false,
    widthSizeClass: WindowWidthSizeClass,
) {
    val authState by authRepository.authState.collectAsStateWithLifecycle()

    when (authState) {
        is AuthState.LoggedOut -> AuthGateNavHost()

        is AuthState.Guest -> MainAppNavHost(
            repository = repository,
            articleRepository = articleRepository,
            taskRepository = taskRepository,
            openTasksOnStart = openTasksOnStart,
            widthSizeClass = widthSizeClass,
        )

        is AuthState.LoggedIn -> MainAppNavHost(
            repository = repository,
            articleRepository = articleRepository,
            taskRepository = taskRepository,
            openTasksOnStart = openTasksOnStart,
            widthSizeClass = widthSizeClass,
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
private fun AuthGateNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginRoute(
                onRegisterClick = { navController.navigate(Routes.REGISTER) },
            )
        }
        composable(Routes.REGISTER) {
            RegisterRoute(
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * The graph that existed before feature 11 — now reached from **both** [AuthState.Guest] and
 * [AuthState.LoggedIn] (feature 13 lifted the mandatory gate) — plus it also owns *startup
 * routing*: reading the persisted `onboarded` flag once to decide whether the user should land on
 * Onboarding or Tasks (feature 18 retired the `HomeScreen` hub that used to sit in between —
 * Tasks is now the sole "already onboarded" landing destination).
 *
 * Reading that flag is asynchronous (it comes from disk via DataStore), so
 * [repository.userPreferences][UserPreferencesRepository.userPreferences] is collected with an
 * `initialValue` of `null`. While it is `null` we show a neutral loading indicator instead of
 * guessing a start destination — otherwise a returning user could see a flash of Onboarding
 * before the real value arrives (see the feature spec's "startup flash" risk).
 *
 * [openTasksOnStart] is set when `MainActivity` was opened by tapping the pending-tasks widget
 * (feature 05) — before feature 18 it overrode the landing destination from Home to Tasks. Now
 * that Tasks *is* the landing destination, it no longer changes the outcome (both paths agree),
 * but the parameter is kept: `MainActivity` still computes and passes it, and it remains a
 * documented, testable seam in case a future feature reintroduces more than one top-level
 * "already onboarded" landing spot. It is (and always was) ignored for a user who has not
 * onboarded yet — Onboarding always wins, so tapping the widget can never skip it.
 *
 * Feature 13 adds [Routes.LOGIN]/[Routes.REGISTER] as ordinary destinations *inside* this graph
 * (reachable from Settings' "Sign in / Create account" entry), distinct from [AuthGateNavHost]'s
 * copies of the same [LoginRoute]/[RegisterRoute] composables. A guest who cancels out of either
 * screen (back arrow) simply pops back to Settings within this same back stack — they are never
 * routed to the mandatory gate, since [AuthState.Guest] never renders [AuthGateNavHost] at all.
 *
 * Feature 18 wraps the [NavHost] below in a [Scaffold] whose `bottomBar` renders [MainBottomBar]
 * — but only while the current destination is one of [TOP_LEVEL_ROUTES], so Article Detail, Task
 * Edit, and Login/Register-from-Settings render full-height instead (Resolved Decision #2 in the
 * feature spec). This `Scaffold` lives strictly inside this function, never in [AppNavHost] or
 * [AuthGateNavHost], so the bottom bar never shows on the login gate.
 *
 * Feature 18b makes this function **width-aware**, without touching a single line of [AppNavHost]
 * above (its `Guest`/`LoggedIn` `when` arms and their guest-mode adoption reasoning are untouched —
 * see that KDoc): on [WindowWidthSizeClass.Compact] the [Scaffold] + [MainBottomBar] pairing above
 * is unchanged; on [WindowWidthSizeClass.Medium]/[WindowWidthSizeClass.Expanded] a
 * [Row] holds [MainNavigationRail] (leading edge) alongside the same [NavHost] instead — see
 * [MainNavigationRail]'s KDoc for why this is the *same* graph, not a duplicated one, and
 * [navigateToTopLevelRoute] for the tab-switch idiom shared by both bar and rail. Either way the
 * chrome stays route-gated by [TOP_LEVEL_ROUTES], and the Articles destination itself branches on
 * [widthSizeClass] a second time, purely internally, to decide one pane vs two (see
 * [ArticlesListDetailPane]'s KDoc) — a decision [MainAppNavHost] threads through but does not make.
 */
@Composable
private fun MainAppNavHost(
    repository: UserPreferencesRepository,
    articleRepository: ArticleRepository,
    taskRepository: TaskRepository,
    navController: NavHostController = rememberNavController(),
    openTasksOnStart: Boolean = false,
    widthSizeClass: WindowWidthSizeClass,
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
            // Feature 18: Home is retired, so Tasks is the sole "already onboarded" landing
            // destination — the openTasksOnStart branch below (see this function's KDoc) now
            // agrees with the default, but is kept explicit rather than folded away.
            val startDestination = when {
                !preferences.onboarded -> Routes.ONBOARDING
                openTasksOnStart -> Routes.TASKS
                else -> Routes.TASKS
            }

            // Observed reactively (not duplicated into local state) so both the bar/rail's
            // visibility and its selected item always agree with the actual back stack — see
            // MainBottomBar/MainNavigationRail below for the same pattern applied to the selected
            // tab.
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route
            // Route-gated visibility (Resolved Decision #2): only the three top-level sections
            // show the bar/rail: Article Detail/Task Edit/Login/Register render full-height
            // instead, at every width.
            val showTopLevelChrome = currentRoute in TOP_LEVEL_ROUTES

            if (widthSizeClass == WindowWidthSizeClass.Compact) {
                Scaffold(
                    bottomBar = {
                        if (showTopLevelChrome) {
                            MainBottomBar(navController = navController)
                        }
                    },
                ) { innerPadding ->
                    MainNavGraph(
                        navController = navController,
                        startDestination = startDestination,
                        articleRepository = articleRepository,
                        widthSizeClass = widthSizeClass,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            } else {
                // Feature 18b: medium/expanded width swaps the bottom bar for a leading-edge rail
                // — same three destinations, same selected-tab derivation, same navigate idiom
                // (see MainNavigationRail's KDoc) — sitting beside the very same NavHost, not a
                // second graph. NavigationRail applies its own window-insets padding, and every
                // per-screen Scaffold inside MainNavGraph applies its own too, so no outer Scaffold
                // is needed here the way the compact branch above needs one for its bottomBar slot.
                Row(modifier = Modifier.fillMaxSize()) {
                    if (showTopLevelChrome) {
                        MainNavigationRail(navController = navController)
                    }
                    MainNavGraph(
                        navController = navController,
                        startDestination = startDestination,
                        articleRepository = articleRepository,
                        widthSizeClass = widthSizeClass,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The nav graph itself — every `composable(...)` destination [MainAppNavHost] hosts — factored out
 * (feature 18b) so both the compact [Scaffold] branch and the medium/expanded [Row] branch in
 * [MainAppNavHost] share the exact same graph instead of declaring it twice. This is the concrete
 * form of this feature's core teaching point: the adaptive layer is a presentation layer *above*
 * one graph, never a duplicated graph.
 *
 * Tasks and Settings (feature 18b: no two-pane split for either this feature — see the spec's Out
 * of Scope) are wrapped in [ReadableWidthContainer] so their single column does not stretch
 * edge-to-edge on a wide window; Articles instead branches on [widthSizeClass] itself:
 * [WindowWidthSizeClass.Expanded] renders [ArticlesListDetailPane] (list + detail side by side),
 * anything narrower keeps the original single-pane [ArticlesRoute] + pushed `ArticleDetail`
 * destination completely unchanged.
 */
@Composable
private fun MainNavGraph(
    navController: NavHostController,
    startDestination: String,
    articleRepository: ArticleRepository,
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingRoute(
                onSaved = {
                    navController.navigate(Routes.TASKS) {
                        // Pop Onboarding off the back stack: after saving, system back
                        // from Tasks must not return the user to Onboarding.
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            ReadableWidthContainer(widthSizeClass = widthSizeClass) {
                SettingsRoute(
                    // Settings is a top-level tab now (feature 18): no back arrow, the
                    // bottom bar/rail replaces it. See TasksScreen/ArticlesScreen for the same.
                    onBack = null,
                    onSignInClick = { navController.navigate(Routes.LOGIN) },
                )
            }
        }
        // Feature 13: login/register reachable from Settings while AuthState.Guest —
        // see MainAppNavHost's KDoc for how this differs from AuthGateNavHost's copies
        // of the same routes. A successful sign-in flips authState to LoggedIn, which
        // AppNavHost reacts to by composing a brand-new MainAppNavHost at a different
        // call site (its own KDoc), so neither destination below needs to navigate
        // anywhere on success.
        composable(Routes.LOGIN) {
            LoginRoute(
                onRegisterClick = { navController.navigate(Routes.REGISTER) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.REGISTER) {
            RegisterRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ARTICLES) {
            // Feature 18b, US-2: expanded width gets the two-pane list-detail split; compact/medium
            // keep the original single-pane list + pushed ArticleDetail destination below,
            // completely unchanged (see ArticlesListDetailPane's KDoc for why its right pane
            // bypasses ArticleDetailViewModel).
            if (widthSizeClass == WindowWidthSizeClass.Expanded) {
                ArticlesListDetailPane(articleRepository = articleRepository)
            } else {
                ArticlesRoute(
                    onArticleClick = { articleId ->
                        navController.navigate("${Routes.ARTICLE_DETAIL}/$articleId")
                    },
                    // Articles is a top-level tab now (feature 18): no back arrow, the
                    // bottom bar/rail replaces it.
                    onBack = null,
                )
            }
        }
        composable(
            // Only the id crosses the navigation boundary — never the full Article —
            // so the detail screen reloads the article from the repository by id.
            // This keeps the route argument small and matches the "no complex objects
            // in routes" constraint from the feature spec. Feature 13d: hiltViewModel()
            // reads this same {articleId} argument off the back stack entry's
            // SavedStateHandle on its own (see ArticleDetailViewModel's KDoc), so this
            // composable no longer needs to read it out by hand. Feature 18b: only reachable
            // at all on compact/medium width — see the Articles branch just above.
            route = "${Routes.ARTICLE_DETAIL}/{$ARG_ARTICLE_ID}",
            arguments = listOf(navArgument(ARG_ARTICLE_ID) { type = NavType.StringType }),
        ) {
            ArticleDetailRoute(
                // Secondary screen (feature 18): keeps its back arrow, and the bottom
                // bar/rail is hidden here (see TOP_LEVEL_ROUTES above).
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.TASKS) { backStackEntry ->
            ReadableWidthContainer(widthSizeClass = widthSizeClass) {
                TasksRoute(
                    onAddTaskClick = { navController.navigate(Routes.TASK_EDIT) },
                    onTaskClick = { taskId -> navController.navigate("${Routes.TASK_EDIT}/$taskId") },
                    // Feature 04c: a top-bar action on Tasks, not a fourth bottom-nav tab
                    // (Out of Scope) — see StatsScreen's KDoc and Routes.STATS below.
                    onStatsClick = { navController.navigate(Routes.STATS) },
                    // Tasks is a top-level tab now (feature 18): no back arrow, the bottom
                    // bar/rail replaces it — it is also the landing destination, so there is
                    // nowhere "back" would go anyway.
                    onBack = null,
                    // This destination's own SavedStateHandle is where the create-only
                    // Task Edit composable below stashes "a task was just created"
                    // (feature 17, US-3) — see TasksRoute's KDoc for why a
                    // SavedStateHandle, not a ViewModel flag, is the right carrier for a
                    // result crossing back from another destination.
                    taskCreatedHandle = backStackEntry.savedStateHandle,
                )
            }
        }
        // Feature 04c: Stats is a *secondary* screen (not in TOP_LEVEL_ROUTES above),
        // reached only from the Tasks top bar — back arrow shown, bottom bar/rail hidden,
        // exactly like Article Detail / Task Edit.
        composable(Routes.STATS) {
            StatsRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.TASK_EDIT) {
            // No {taskId} argument on this route: TaskEditRoute below gets a null
            // taskId, which is exactly what tells TaskEditViewModel to create a new
            // task instead of loading an existing one.
            TaskEditRoute(
                taskId = null,
                onSaved = {
                    // Reaching onSaved here always means "created": this composable
                    // has no {taskId} argument, so TaskEditViewModel.deleteTask is a
                    // no-op (see its KDoc) and the only way isSaved ever flips true is
                    // a successful create. Stash that on Tasks' own back stack entry —
                    // the standard Navigation Compose way to pass a one-shot result
                    // back to the previous screen — before popping back to it.
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(TASK_CREATED_RESULT_KEY, true)
                    navController.popBackStack()
                },
                // Secondary screen (feature 18): keeps its back arrow.
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "${Routes.TASK_EDIT}/{$ARG_TASK_ID}",
            arguments = listOf(navArgument(ARG_TASK_ID) { type = NavType.LongType }),
        ) { backStackEntry ->
            // Only the id crosses the navigation boundary — never the full Task — so
            // the edit screen reloads the task from the repository by id, same
            // reasoning as the article detail route above.
            val taskId = backStackEntry.arguments?.getLong(ARG_TASK_ID)
            TaskEditRoute(
                taskId = taskId,
                onSaved = { navController.popBackStack() },
                // Secondary screen (feature 18): keeps its back arrow.
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * One entry of the bottom bar: the [route] it navigates to, the label/content-description string
 * resources, and the icon to show. A small private list (mirroring [themeOptions]-style fixed
 * option lists elsewhere in the app, e.g. `SettingsScreen.kt`) instead of hardcoding three
 * [NavigationBarItem]s keeps [MainBottomBar] a simple `forEach` loop.
 */
private data class BottomNavItem(
    val route: String,
    val labelRes: Int,
    val contentDescriptionRes: Int,
    val icon: ImageVector,
)

private val bottomNavItems = listOf(
    BottomNavItem(
        route = Routes.TASKS,
        labelRes = R.string.nav_tasks,
        contentDescriptionRes = R.string.nav_tasks_content_description,
        icon = Icons.AutoMirrored.Filled.Assignment,
    ),
    BottomNavItem(
        route = Routes.ARTICLES,
        labelRes = R.string.nav_articles,
        contentDescriptionRes = R.string.nav_articles_content_description,
        icon = Icons.AutoMirrored.Filled.MenuBook,
    ),
    BottomNavItem(
        route = Routes.SETTINGS,
        labelRes = R.string.nav_settings,
        contentDescriptionRes = R.string.nav_settings_content_description,
        icon = Icons.Filled.Settings,
    ),
)

/**
 * The canonical Navigation Compose tab-switch idiom (feature 18), factored into one shared
 * extension (feature 18b) so [MainBottomBar] (compact) and [MainNavigationRail]
 * (medium/expanded) can never drift into two subtly different idioms — "extend, don't duplicate"
 * applied to navigation logic, the same rule the tutorial already applies to domain code:
 * - `popUpTo(graph.findStartDestination().id) { saveState = true }` keeps the back stack from
 *   growing unbounded as the user hops between tabs, while saving each tab's own state.
 * - `launchSingleTop = true` avoids stacking a second copy of a tab the user re-taps.
 * - `restoreState = true` brings back that saved state (e.g. scroll position) when returning to a
 *   tab visited before.
 */
private fun NavHostController.navigateToTopLevelRoute(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Feature 18's persistent bottom [NavigationBar]: top-level / lateral navigation between the
 * app's three main sections, as opposed to the push-style `navigate()` every other destination in
 * [MainAppNavHost] uses. Unlike a push, selecting a tab is a lateral switch between peer sections.
 * Shown only on [WindowWidthSizeClass.Compact] (feature 18b) — see [MainNavigationRail] for the
 * medium/expanded equivalent, which reuses the same [bottomNavItems] and the same
 * [navigateToTopLevelRoute] idiom below.
 *
 * The selected item is derived from [currentBackStackEntryAsState] — the live back stack — rather
 * than any `remember`ed index, so it can never drift out of sync with the actual current screen
 * (US-2). [NavDestination.hierarchy] (not just [NavDestination.route]) is checked because a
 * destination's "hierarchy" includes its parent graphs, which future nested-graph refactors could
 * introduce between a leaf destination and this one.
 */
@Composable
private fun MainBottomBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar {
        bottomNavItems.forEach { item ->
            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = { navController.navigateToTopLevelRoute(item.route) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = stringResource(item.contentDescriptionRes),
                    )
                },
                label = { Text(stringResource(item.labelRes)) },
            )
        }
    }
}

/**
 * Feature 18b's leading-edge [NavigationRail]: the medium/expanded counterpart of [MainBottomBar]
 * — same [bottomNavItems], same reactive selected-tab derivation off the live back stack, same
 * [navigateToTopLevelRoute] tab-switch idiom, just laid out vertically on the side instead of
 * horizontally at the bottom. This is the concrete proof that swapping bar for rail is a pure
 * *presentation* choice: neither composable owns any navigation state of its own, and
 * [MainAppNavHost] never builds a second [NavHost]/graph to support this — see that function's
 * KDoc.
 */
@Composable
private fun MainNavigationRail(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationRail {
        bottomNavItems.forEach { item ->
            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
            NavigationRailItem(
                selected = selected,
                onClick = { navController.navigateToTopLevelRoute(item.route) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = stringResource(item.contentDescriptionRes),
                    )
                },
                label = { Text(stringResource(item.labelRes)) },
            )
        }
    }
}

@Composable
private fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Preview(name = "Compact — bottom bar", widthDp = 400, heightDp = 120)
@Composable
private fun MainBottomBarPreview() {
    NeverLateTheme {
        MainBottomBar(navController = rememberNavController())
    }
}

@Preview(name = "Medium/Expanded — rail", widthDp = 96, heightDp = 600)
@Composable
private fun MainNavigationRailPreview() {
    NeverLateTheme {
        MainNavigationRail(navController = rememberNavController())
    }
}
