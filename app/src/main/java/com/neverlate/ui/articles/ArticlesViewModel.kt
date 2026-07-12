package com.neverlate.ui.articles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.neverlate.data.articles.Article
import com.neverlate.data.articles.ArticleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Drives the Articles list screen with **Jetpack Paging 3**: [articles] is a stream of
 * [PagingData], not a one-shot [List] the way [ArticleDetailViewModel]'s single-article read still
 * is — the list keeps loading more pages as the UI scrolls, and [ArticlesScreen] renders whatever
 * has arrived so far via `collectAsLazyPagingItems()`.
 *
 * Through feature 13b this ViewModel held a hand-rolled `ArticlesUiState` (loading/content/empty/
 * error) plus an `isRefreshing` flag, refreshed by a *stale-while-revalidate* `loadThenRefresh()`
 * loop that called [ArticleRepository]'s old `getArticles()`/`refresh()` pair. Paging 3 replaces
 * all of that: [com.neverlate.data.articles.ArticlesRemoteMediator] is where the equivalent
 * "network writes, cache reads" logic now lives, and `LazyPagingItems.loadState` (inspected in
 * [ArticlesScreen]) is where loading/error/empty now come from — there is nothing left for this
 * ViewModel to compute itself.
 *
 * [cachedIn] shares one upstream [Flow] of [PagingData] across configuration changes (e.g. a
 * screen rotation) by keying it to [viewModelScope]: without it, every recomposition that
 * re-collects [articles] would restart paging from page 0.
 *
 * Feature 13d: `@HiltViewModel` + `@Inject constructor` — obtained via `hiltViewModel()` in
 * [ArticlesRoute] instead of the retired `AppViewModelFactory`.
 */
@HiltViewModel
class ArticlesViewModel @Inject constructor(repository: ArticleRepository) : ViewModel() {
    val articles: Flow<PagingData<Article>> = repository.articlesPager().cachedIn(viewModelScope)
}
