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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.ui.home.HomeRoute
import com.neverlate.ui.onboarding.OnboardingRoute

/** Destination names for the nav graph, kept as constants so routes can't be mistyped. */
private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
}

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
                    HomeRoute(repository = repository)
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
