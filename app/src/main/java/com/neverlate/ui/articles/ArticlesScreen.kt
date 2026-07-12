package com.neverlate.ui.articles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.neverlate.R
import com.neverlate.data.articles.Article
import com.neverlate.ui.components.BrandIconChip
import com.neverlate.ui.components.MessageState
import com.neverlate.ui.components.brandedTopAppBarColors
import com.neverlate.ui.theme.NeverLateTheme
import kotlinx.coroutines.flow.flowOf

/**
 * Stateful wrapper: obtains [ArticlesViewModel] via `hiltViewModel()` (feature 13d) and turns its
 * [ArticlesViewModel.articles] stream into a [LazyPagingItems] snapshot via
 * `collectAsLazyPagingItems()` — the Compose-Paging bridge that gives [ArticlesScreen] something
 * it can index/iterate like a list, while still loading more pages behind the scenes as the user
 * scrolls. Same route/screen split used for Onboarding (see
 * [com.neverlate.ui.onboarding.OnboardingRoute]).
 *
 * [onBack] is `null` when Articles is reached as a top-level bottom-bar tab (feature 18) — there
 * is no back arrow to show in that case, since the bar itself is the way to leave this screen.
 */
@Composable
fun ArticlesRoute(
    onArticleClick: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: ArticlesViewModel = hiltViewModel(),
) {
    val articles = viewModel.articles.collectAsLazyPagingItems()
    ArticlesScreen(
        articles = articles,
        onArticleClick = onArticleClick,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Renders a [LazyPagingItems] snapshot and reports user intent through callbacks only (state
 * hoisting), same as [com.neverlate.ui.onboarding.OnboardingScreen] — [articles] is itself the
 * hoisted state here, Paging's own observable wrapper around the current page of loaded items.
 *
 * Feature 13b drove this screen off a hand-written `ArticlesUiState` (loading/content/empty/error)
 * computed by the ViewModel. Feature 13c replaces that with [LazyPagingItems.loadState]: `refresh`
 * (first load / pull-to-refresh) drives the [PullToRefreshBox] spinner and, on failure with an
 * empty list, the full-screen error [MessageState]; `append` (loading the next page while
 * scrolling) drives the bottom-of-list spinner/retry inside [ArticleList].
 *
 * `PullToRefreshBox` (Material 3, still `@ExperimentalMaterial3Api`) wraps the whole content area
 * below the top bar: it recognizes a downward swipe gesture and calls
 * [LazyPagingItems.refresh] when the user releases past its threshold — which re-runs the
 * [androidx.paging.RemoteMediator]'s `REFRESH` load, exactly like the on-screen retry button does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticlesScreen(
    articles: LazyPagingItems<Article>,
    onArticleClick: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val refreshState = articles.loadState.refresh
    val isRefreshing = refreshState is LoadState.Loading

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.articles_title)) },
                navigationIcon = {
                    // Only rendered as a secondary screen (Resolved Decision #2): as a top-level
                    // bottom-bar tab (feature 18), onBack is null and this slot stays empty.
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.articles_back_content_description),
                            )
                        }
                    }
                },
                colors = brandedTopAppBarColors(),
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { articles.refresh() },
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            when {
                // Nothing to show yet: avoids a one-frame flash of the empty state while the
                // first page is still in flight.
                refreshState is LoadState.Loading && articles.itemCount == 0 -> Unit
                // First load failed and there is nothing cached to fall back on. Retry re-runs
                // the exact same RemoteMediator.REFRESH that pull-to-refresh triggers.
                refreshState is LoadState.Error && articles.itemCount == 0 -> MessageState(
                    icon = Icons.Filled.ErrorOutline,
                    message = stringResource(R.string.articles_error),
                    actionLabel = stringResource(R.string.articles_retry),
                    onAction = { articles.retry() },
                    modifier = Modifier.fillMaxSize(),
                )
                // Loaded successfully but the catalog is genuinely empty.
                articles.itemCount == 0 -> MessageState(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    message = stringResource(R.string.articles_empty),
                    modifier = Modifier.fillMaxSize(),
                )
                else -> ArticleList(
                    articles = articles,
                    onArticleClick = onArticleClick,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ArticleList(articles: LazyPagingItems<Article>, onArticleClick: (String) -> Unit, modifier: Modifier = Modifier) {
    // LazyColumn only composes/measures the rows currently visible on screen (unlike a
    // scrollable Column, which would build every row up front) — the standard Compose widget
    // for potentially long lists. This is the count/key/contentType overload LazyListScope
    // provides directly (no separate import needed, unlike the List-based `items(list, key)`
    // extension this screen used pre-Paging): `itemKey`/`itemContentType` (androidx.paging.compose)
    // adapt LazyPagingItems' possibly-not-yet-loaded placeholders to that shape.
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(
            count = articles.itemCount,
            key = articles.itemKey { it.id },
            contentType = articles.itemContentType { "article" },
        ) { index ->
            val article = articles[index]
            if (article != null) {
                // Modifier.animateItem() (feature 17) animates this row's placement whenever the
                // list around it changes shape — insertion, removal, or reorder — for free, as
                // long as the item's `key` above stays stable across recompositions (it already
                // is, since itemKey tracks Article.id).
                ArticleRow(
                    article = article,
                    onClick = { onArticleClick(article.id) },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        when (articles.loadState.append) {
            is LoadState.Loading -> item { AppendLoadingRow() }
            is LoadState.Error -> item { AppendErrorRow(onRetry = { articles.retry() }) }
            is LoadState.NotLoading -> Unit
        }
    }
}

/** Shown as the list's last row while the next page is loading (US-1's `prefetchDistance`-driven append). */
@Composable
private fun AppendLoadingRow(modifier: Modifier = Modifier) {
    // Resolved outside the semantics{} lambda: that lambda runs in a plain
    // SemanticsPropertyReceiver scope, not a @Composable one, so stringResource can't be called
    // directly inside it.
    val loadingMoreDescription = stringResource(R.string.articles_loading_more_content_description)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(32.dp)
                // No sibling text next to a lone spinner like MessageState's icon has, so this one
                // carries its own contentDescription instead of being marked decorative.
                .semantics { contentDescription = loadingMoreDescription },
        )
    }
}

/**
 * Shown as the list's last row when loading the next page fails (distinct from the full-screen
 * [MessageState] error, which only applies to a *first* load with nothing cached). Existing rows
 * above stay visible and scrollable — only this trailing row communicates the failure.
 */
@Composable
private fun AppendErrorRow(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.articles_append_error),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        // Feature 18's minimumInteractiveComponentSize idiom (see MessageState): Material 3's
        // TextButton defaults below the 48dp accessibility touch-target guideline.
        TextButton(onClick = onRetry, modifier = Modifier.minimumInteractiveComponentSize()) {
            Text(stringResource(R.string.articles_retry))
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
            // Feature 20: the branded leading-icon chip, echoing the mockup's `.leading` element.
            // Decorative (default contentDescription = null): the headline above already carries
            // the row's meaning.
            leadingContent = { BrandIconChip(icon = Icons.AutoMirrored.Filled.MenuBook) },
        )
    }
}

private val pomodoroPreview = Article(
    id = "pomodoro",
    title = "La técnica Pomodoro",
    summary = "Divide el trabajo en bloques cortos de 25 minutos.",
    body = "",
)

private val timeBlockingPreview = Article(
    id = "time-blocking",
    title = "Time blocking",
    summary = "Asigna cada hora del día a una tarea concreta.",
    body = "",
)

@Preview(showBackground = true)
@Composable
private fun ArticlesScreenContentPreview() {
    NeverLateTheme {
        val articles = flowOf(PagingData.from(listOf(pomodoroPreview, timeBlockingPreview))).collectAsLazyPagingItems()
        ArticlesScreen(articles = articles, onArticleClick = {}, onBack = null)
    }
}

@Preview(showBackground = true)
@Composable
private fun ArticlesScreenEmptyPreview() {
    NeverLateTheme {
        val articles = flowOf(PagingData.empty<Article>()).collectAsLazyPagingItems()
        ArticlesScreen(articles = articles, onArticleClick = {}, onBack = null)
    }
}

@Preview(showBackground = true)
@Composable
private fun ArticlesScreenErrorPreview() {
    NeverLateTheme {
        val errorLoadStates = LoadStates(
            refresh = LoadState.Error(Throwable("preview")),
            prepend = LoadState.NotLoading(endOfPaginationReached = true),
            append = LoadState.NotLoading(endOfPaginationReached = true),
        )
        val articles = flowOf(PagingData.empty<Article>(sourceLoadStates = errorLoadStates)).collectAsLazyPagingItems()
        ArticlesScreen(articles = articles, onArticleClick = {}, onBack = null)
    }
}
