package com.neverlate.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neverlate.R
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.ui.navigation.AppViewModelFactory
import com.neverlate.ui.theme.NeverLateTheme
import kotlinx.coroutines.launch

/**
 * Stateful wrapper: obtains [HomeViewModel] (via [AppViewModelFactory]) and forwards its state
 * to the stateless [HomeScreen] below, following the same route/screen split used for
 * Onboarding (see [com.neverlate.ui.onboarding.OnboardingRoute]).
 */
@Composable
fun HomeRoute(
    repository: UserPreferencesRepository,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelFactory(repository)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(uiState = uiState, modifier = modifier)
}

/** A single placeholder entry rendered on Home (Tasks, Articles, ...). */
private data class HomeOption(val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(uiState: HomeUiState, modifier: Modifier = Modifier) {
    // A Snackbar is triggered from a click callback, which is not a suspend function, so we
    // need our own CoroutineScope (tied to this composable) to launch the suspend `showSnackbar`
    // call from inside it.
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val comingSoonMessage = stringResource(R.string.home_option_coming_soon)

    val options = listOf(
        HomeOption(stringResource(R.string.home_option_tasks)),
        HomeOption(stringResource(R.string.home_option_articles)),
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.home_greeting, uiState.name),
                style = MaterialTheme.typography.headlineSmall,
            )

            Column(
                modifier = Modifier.padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    HomeOptionCard(
                        label = option.label,
                        onClick = {
                            coroutineScope.launch { snackbarHostState.showSnackbar(comingSoonMessage) }
                        },
                    )
                }
            }
        }
    }
}

/** One clickable placeholder row, styled as a card. Not wired to any real feature yet. */
@Composable
private fun HomeOptionCard(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        ListItem(headlineContent = { Text(label) })
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    NeverLateTheme {
        HomeScreen(uiState = HomeUiState(name = "Ada"))
    }
}
