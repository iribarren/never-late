package com.neverlate.ui.articles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.neverlate.R
import com.neverlate.data.articles.ArticleRepository
import com.neverlate.ui.components.MessageState
import com.neverlate.ui.theme.NeverLateTheme

/**
 * Feature 18b: the expanded-width two-pane Articles screen — the paginated list (left pane) is
 * [ArticlesRoute], completely unchanged; the right pane shows the tapped article's detail in
 * place, with no full-screen navigation and no back arrow (the list stays visible alongside it).
 *
 * Built on `ListDetailPaneScaffold` (`androidx.compose.material3.adaptive`), the named adaptive API
 * for exactly this pattern (see the version catalog's `composeMaterial3Adaptive` pin comment for
 * why 1.0.0, not the newest release) — the alternative the feature spec allowed was a manual
 * two-pane `Row(ArticleList, ArticleDetailScreen)`, which turned out not to be necessary: 1.0.0
 * builds cleanly against this project's Compose BOM/AGP pin.
 *
 * [rememberListDetailPaneScaffoldNavigator] tracks which pane is "current" and, on narrower widths
 * than expanded, would collapse to a single visible pane on its own — this project still routes
 * compact/medium through the ordinary single-pane [ArticlesRoute] + pushed `ArticleDetail`
 * destination instead (see `AppNavHost`'s `Routes.ARTICLES` composable), so this pane is only ever
 * reached at expanded width, but the navigator's own back handling is wired via [BackHandler]
 * regardless, so system back first clears a selection before leaving Articles entirely.
 *
 * The right pane does **not** go through `hiltViewModel()`/[ArticleDetailViewModel]: that ViewModel
 * reads its `articleId` from a `SavedStateHandle` tied to a navigation back-stack entry (feature
 * 13d) — exactly right for the compact/medium *pushed* Article Detail route, but there is no
 * navigation event here at all, just a locally-held selection. Instead [ArticleDetailPaneContent]
 * loads the selected article directly from [articleRepository] (the same `getArticleById` the
 * pushed-route ViewModel calls) and renders it with the same [ArticleDetailUiState] +
 * [ArticleDetailBody] the compact path already uses — reusing the rendering, not the ViewModel
 * plumbing, per the feature spec's approved decision.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ArticlesListDetailPane(
    articleRepository: ArticleRepository,
    modifier: Modifier = Modifier,
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    BackHandler(enabled = navigator.canNavigateBack()) {
        navigator.navigateBack()
    }

    ListDetailPaneScaffold(
        modifier = modifier,
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                ArticlesRoute(
                    onArticleClick = { articleId ->
                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, articleId)
                    },
                    // Articles is a top-level tab even in two-pane mode (feature 18): no back
                    // arrow on the list pane either.
                    onBack = null,
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val selectedArticleId = navigator.currentDestination?.content
                if (selectedArticleId != null) {
                    ArticleDetailPaneContent(
                        articleId = selectedArticleId,
                        articleRepository = articleRepository,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // US-2: a neutral placeholder on first entry, before any row has been tapped —
                    // never a blank void or a crash.
                    MessageState(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        message = stringResource(R.string.articles_select_placeholder),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
    )
}

/**
 * Loads [articleId] straight from [articleRepository] (bypassing [ArticleDetailViewModel] — see
 * [ArticlesListDetailPane]'s KDoc for why) and renders it through the same [ArticleDetailUiState] +
 * [ArticleDetailBody] the compact/medium pushed Article Detail route uses, so the two paths can
 * never visually drift apart. Re-runs whenever [articleId] changes (the user taps a different list
 * row while this pane stays composed).
 */
@Composable
private fun ArticleDetailPaneContent(
    articleId: String,
    articleRepository: ArticleRepository,
    modifier: Modifier = Modifier,
) {
    var uiState by remember(articleId) { mutableStateOf<ArticleDetailUiState>(ArticleDetailUiState.Loading) }

    LaunchedEffect(articleId) {
        val article = articleRepository.getArticleById(articleId)
        uiState = if (article != null) ArticleDetailUiState.Content(article) else ArticleDetailUiState.NotFound
    }

    ArticleDetailBody(uiState = uiState, modifier = modifier)
}

/**
 * Shows just the expanded-width shell (list pane stub + the "nothing selected" placeholder in the
 * detail pane) — a full preview of [ArticlesListDetailPane] itself is not practical since its list
 * pane is [ArticlesRoute], a `hiltViewModel()`-backed stateful composable that needs a real Hilt
 * graph (previews don't run through one), the same reason every other `*Route` in this project is
 * previewed via its stateless `*Screen` instead. [ArticleDetailBody]'s own previews (in
 * `ArticleDetailScreen.kt`) already cover the detail pane's Content/NotFound states.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Preview(name = "Expanded — nothing selected", widthDp = 1000, heightDp = 800)
@Composable
private fun ArticlesListDetailPanePlaceholderPreview() {
    NeverLateTheme {
        val navigator = rememberListDetailPaneScaffoldNavigator<String>()
        ListDetailPaneScaffold(
            directive = navigator.scaffoldDirective,
            value = navigator.scaffoldValue,
            listPane = {
                AnimatedPane {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Text("Article list")
                    }
                }
            },
            detailPane = {
                AnimatedPane {
                    MessageState(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        message = stringResource(R.string.articles_select_placeholder),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
        )
    }
}
