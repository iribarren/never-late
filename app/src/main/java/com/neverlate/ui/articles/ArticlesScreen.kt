package com.neverlate.ui.articles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neverlate.R
import com.neverlate.data.articles.Article
import com.neverlate.data.articles.ArticleRepository
import com.neverlate.ui.navigation.AppViewModelFactory
import com.neverlate.ui.theme.NeverLateTheme

/**
 * Stateful wrapper: obtains [ArticlesViewModel] (via [AppViewModelFactory]) and forwards its
 * state to the stateless [ArticlesScreen], following the same route/screen split used for Home
 * and Onboarding (see [com.neverlate.ui.home.HomeRoute]).
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
    ArticlesScreen(uiState = uiState, onArticleClick = onArticleClick, onBack = onBack, modifier = modifier)
}

/**
 * Stateless composable: renders an [ArticlesUiState] and reports user intent through callbacks
 * only (state hoisting), same as [com.neverlate.ui.onboarding.OnboardingScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticlesScreen(
    uiState: ArticlesUiState,
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
        when (uiState) {
            // Nothing to show yet: avoids a one-frame flash of the empty state while loading.
            is ArticlesUiState.Loading -> Unit
            is ArticlesUiState.Empty -> EmptyArticles(modifier = Modifier.padding(innerPadding))
            is ArticlesUiState.Content -> ArticleList(
                articles = uiState.articles,
                onArticleClick = onArticleClick,
                modifier = Modifier.padding(innerPadding),
            )
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
            ArticleRow(article = article, onClick = { onArticleClick(article.id) })
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

@Composable
private fun EmptyArticles(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.articles_empty))
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
            onArticleClick = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ArticlesScreenEmptyPreview() {
    NeverLateTheme {
        ArticlesScreen(uiState = ArticlesUiState.Empty, onArticleClick = {}, onBack = {})
    }
}
