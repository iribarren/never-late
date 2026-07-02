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

/**
 * Stateful wrapper: obtains [HomeViewModel] (via [AppViewModelFactory]) and forwards its state
 * to the stateless [HomeScreen] below, following the same route/screen split used for
 * Onboarding (see [com.neverlate.ui.onboarding.OnboardingRoute]).
 */
@Composable
fun HomeRoute(
    repository: UserPreferencesRepository,
    onArticlesClick: () -> Unit,
    onTasksClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelFactory(repository)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(uiState = uiState, onArticlesClick = onArticlesClick, onTasksClick = onTasksClick, modifier = modifier)
}

/** A single entry rendered on Home (Tasks, Articles, ...), each with its own click behaviour. */
private data class HomeOption(val label: String, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onArticlesClick: () -> Unit,
    onTasksClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Both options now navigate directly (Tasks and Articles are both implemented), but the
    // SnackbarHost stays wired up: it's the natural place for a future Home option that isn't
    // ready yet to show a brief "coming soon" message instead of navigating.
    val snackbarHostState = remember { SnackbarHostState() }

    val options = listOf(
        HomeOption(stringResource(R.string.home_option_tasks), onTasksClick),
        HomeOption(stringResource(R.string.home_option_articles), onArticlesClick),
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
                    HomeOptionCard(label = option.label, onClick = option.onClick)
                }
            }
        }
    }
}

/** One clickable row on Home, styled as a card. */
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
        HomeScreen(uiState = HomeUiState(name = "Ada"), onArticlesClick = {}, onTasksClick = {})
    }
}
