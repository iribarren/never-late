package com.neverlate.ui.articles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neverlate.R
import com.neverlate.data.articles.Article
import com.neverlate.ui.components.brandedTopAppBarColors
import com.neverlate.ui.theme.NeverLateTheme

/**
 * Stateful wrapper: obtains [ArticleDetailViewModel] via `hiltViewModel()` (feature 13d) — Hilt
 * reads the `articleId` navigation argument off the current `NavBackStackEntry`'s
 * `SavedStateHandle` on its own (see [ArticleDetailViewModel]'s KDoc), so this Route no longer
 * needs an `articleId`/`articleRepository` parameter of its own to build the ViewModel by hand —
 * and forwards its state to the stateless [ArticleDetailScreen].
 */
@Composable
fun ArticleDetailRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArticleDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ArticleDetailScreen(uiState = uiState, onBack = onBack, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleDetailScreen(
    uiState: ArticleDetailUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(articleDetailTitle(uiState)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.articles_back_content_description),
                        )
                    }
                },
                colors = brandedTopAppBarColors(),
            )
        },
    ) { innerPadding ->
        ArticleDetailBody(uiState = uiState, modifier = Modifier.padding(innerPadding))
    }
}

/**
 * The content-only half of [ArticleDetailScreen] — no [Scaffold]/`TopAppBar`/back arrow, just the
 * `when` over [ArticleDetailUiState] that used to live directly inside [ArticleDetailScreen]'s
 * content slot. Split out (feature 18b) so the expanded-width two-pane Articles' right pane
 * ([ArticlesListDetailPane]) can reuse the exact same rendering without also getting a second top
 * bar/back arrow nested inside the list-detail scaffold's detail pane — see that pane's KDoc for
 * why it has no chrome of its own.
 */
@Composable
fun ArticleDetailBody(uiState: ArticleDetailUiState, modifier: Modifier = Modifier) {
    when (uiState) {
        is ArticleDetailUiState.Loading -> Unit
        is ArticleDetailUiState.NotFound -> NotFoundMessage(modifier = modifier)
        is ArticleDetailUiState.Content -> ArticleBody(article = uiState.article, modifier = modifier)
    }
}

@Composable
private fun articleDetailTitle(uiState: ArticleDetailUiState) = when (uiState) {
    is ArticleDetailUiState.Content -> uiState.article.title
    else -> stringResource(R.string.articles_detail_title_fallback)
}

@Composable
private fun ArticleBody(article: Article, modifier: Modifier = Modifier) {
    // verticalScroll (instead of a LazyColumn) is enough here: a single article body is one
    // block of text, not a list of independent items that would benefit from lazy composition.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(text = article.title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = article.body,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun NotFoundMessage(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.articles_not_found))
    }
}

@Preview(showBackground = true)
@Composable
private fun ArticleDetailScreenContentPreview() {
    NeverLateTheme {
        ArticleDetailScreen(
            uiState = ArticleDetailUiState.Content(
                Article(
                    id = "pomodoro",
                    title = "La técnica Pomodoro",
                    summary = "Divide el trabajo en bloques cortos de 25 minutos.",
                    body = "Cuerpo de ejemplo para la vista previa del detalle de un artículo.",
                ),
            ),
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ArticleDetailScreenNotFoundPreview() {
    NeverLateTheme {
        ArticleDetailScreen(uiState = ArticleDetailUiState.NotFound, onBack = {})
    }
}
