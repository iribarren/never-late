package com.neverlate.ui.articles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neverlate.R
import com.neverlate.data.articles.Article
import com.neverlate.data.articles.ArticleRepository
import com.neverlate.ui.components.MessageState
import com.neverlate.ui.navigation.AppViewModelFactory
import com.neverlate.ui.theme.NeverLateTheme

/**
 * Stateful wrapper: obtains [ArticlesViewModel] (via [AppViewModelFactory]) and forwards its
 * state to the stateless [ArticlesScreen], following the same route/screen split used for Home
 * and Onboarding (see [com.neverlate.ui.home.HomeRoute]).
 *
 * [ArticlesViewModel.isRefreshing] and [ArticlesViewModel.refresh] are collected/forwarded here
 * alongside [ArticlesViewModel.uiState], feature 10's additions to this ViewModel: the former
 * drives [ArticlesScreen]'s pull-to-refresh spinner, the latter is what both that gesture and the
 * Error state's retry button call.
 */
@Composable
fun ArticlesRoute(
    articleRepository: ArticleRepository,
    onArticleClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArticlesViewModel = viewModel(
        factory = AppViewModelFactory(articleRepository = articleRepository),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    ArticlesScreen(
        uiState = uiState,
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        onArticleClick = onArticleClick,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Stateless composable: renders an [ArticlesUiState] and reports user intent through callbacks
 * only (state hoisting), same as [com.neverlate.ui.onboarding.OnboardingScreen].
 *
 * `PullToRefreshBox` (Material 3, still `@ExperimentalMaterial3Api`) wraps the whole content area
 * below the top bar: it recognizes a downward swipe gesture on its content and calls [onRefresh]
 * when the user releases past its threshold, showing a spinner driven by [isRefreshing] while the
 * refresh is in flight. It works the same regardless of which [uiState] is currently displayed
 * underneath — including [ArticlesUiState.Error], so a failed load can also be retried by
 * swiping down, not just by tapping the on-screen retry button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticlesScreen(
    uiState: ArticlesUiState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onArticleClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.articles_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.articles_back_content_description),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            when (uiState) {
                // Nothing to show yet: avoids a one-frame flash of the empty state while loading.
                is ArticlesUiState.Loading -> Unit
                is ArticlesUiState.Empty -> MessageState(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    message = stringResource(R.string.articles_empty),
                    modifier = Modifier.fillMaxSize(),
                )
                // Retry reuses the exact same ArticlesViewModel.refresh that pull-to-refresh
                // calls (see ArticlesRoute), so both paths retry identically.
                is ArticlesUiState.Error -> MessageState(
                    icon = Icons.Filled.ErrorOutline,
                    message = stringResource(R.string.articles_error),
                    actionLabel = stringResource(R.string.articles_retry),
                    onAction = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                )
                is ArticlesUiState.Content -> ArticleList(
                    articles = uiState.articles,
                    onArticleClick = onArticleClick,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ArticleList(articles: List<Article>, onArticleClick: (String) -> Unit, modifier: Modifier = Modifier) {
    // LazyColumn only composes/measures the rows currently visible on screen (unlike a
    // scrollable Column, which would build every row up front) — the standard Compose widget
    // for potentially long lists. `key = { it.id }` lets Compose track each row by its stable
    // article id across recompositions, instead of by list position.
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(articles, key = { it.id }) { article ->
            // Modifier.animateItem() (feature 17) animates this row's placement whenever the
            // list around it changes shape — insertion, removal, or reorder — for free, as long
            // as the item's `key` above stays stable across recompositions (it already is).
            ArticleRow(
                article = article,
                onClick = { onArticleClick(article.id) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun ArticleRow(article: Article, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        ListItem(
            headlineContent = { Text(article.title) },
            supportingContent = { Text(article.summary) },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ArticlesScreenContentPreview() {
    NeverLateTheme {
        ArticlesScreen(
            uiState = ArticlesUiState.Content(
                listOf(
                    Article(
                        id = "pomodoro",
                        title = "La técnica Pomodoro",
                        summary = "Divide el trabajo en bloques cortos de 25 minutos.",
                        body = "",
                    ),
                    Article(
                        id = "time-blocking",
                        title = "Time blocking",
                        summary = "Asigna cada hora del día a una tarea concreta.",
                        body = "",
                    ),
                ),
            ),
            isRefreshing = false,
            onRefresh = {},
            onArticleClick = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ArticlesScreenEmptyPreview() {
    NeverLateTheme {
        ArticlesScreen(
            uiState = ArticlesUiState.Empty,
            isRefreshing = false,
            onRefresh = {},
            onArticleClick = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ArticlesScreenErrorPreview() {
    NeverLateTheme {
        ArticlesScreen(
            uiState = ArticlesUiState.Error,
            isRefreshing = false,
            onRefresh = {},
            onArticleClick = {},
            onBack = {},
        )
    }
}
