package com.neverlate.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
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
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.ui.articles.ArticleDetailRoute
import com.neverlate.ui.articles.ArticlesRoute
import com.neverlate.ui.home.HomeRoute
import com.neverlate.ui.onboarding.OnboardingRoute
import com.neverlate.ui.tasks.TaskEditRoute
import com.neverlate.ui.tasks.TasksRoute

/** Destination names for the nav graph, kept as constants so routes can't be mistyped. */
private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ARTICLES = "articles"
    const val ARTICLE_DETAIL = "articleDetail"
    const val TASKS = "tasks"
    const val TASK_EDIT = "taskEdit"
}

/** Name of the navigation argument carrying an [com.neverlate.data.articles.Article.id]. */
private const val ARG_ARTICLE_ID = "articleId"

/** Name of the navigation argument carrying a [com.neverlate.data.tasks.Task.id]. */
private const val ARG_TASK_ID = "taskId"

/**
 * App-wide navigation graph (Navigation Compose). It also owns *startup routing*: reading the
 * persisted `onboarded` flag once to decide whether the user should land on Onboarding or Home.
 *
 * Reading that flag is asynchronous (it comes from disk via DataStore), so
 * [repository.userPreferences][UserPreferencesRepository.userPreferences] is collected with an
 * `initialValue` of `null`. While it is `null` we show a neutral loading indicator instead of
 * guessing a start destination — otherwise a returning user could see a flash of Onboarding
 * before the real value arrives (see the feature spec's "startup flash" risk).
 */
@Composable
fun AppNavHost(
    repository: UserPreferencesRepository,
    articleRepository: ArticleRepository,
    taskRepository: TaskRepository,
    navController: NavHostController = rememberNavController(),
) {
    val userPreferences by repository.userPreferences.collectAsStateWithLifecycle(initialValue = null)

    when (val preferences = userPreferences) {
        null -> LoadingIndicator()
        else -> {
            val startDestination = if (preferences.onboarded) Routes.HOME else Routes.ONBOARDING

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
