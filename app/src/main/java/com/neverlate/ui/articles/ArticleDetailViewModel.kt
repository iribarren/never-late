package com.neverlate.ui.articles

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.articles.Article
import com.neverlate.data.articles.ArticleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** [SavedStateHandle] key for the `articleId` navigation argument — see `AppNavHost`'s `Routes.ARTICLE_DETAIL`. */
private const val ARG_ARTICLE_ID = "articleId"

/**
 * Everything the Article detail screen needs to render itself.
 *
 * Through feature 13b this also had an `Error` case: a cache miss first tried
 * [ArticleRepository]'s old `refresh()`, and only settled on [NotFound] if that network fetch also
 * failed to find the id, or on `Error` if the fetch itself failed. Feature 13c removes that
 * fallback along with `refresh()` itself (see [com.neverlate.data.articles.ArticleRepository]'s
 * KDoc): the article list is now kept warm by
 * [com.neverlate.data.articles.ArticlesRemoteMediator] as the user scrolls it, so by the time a
 * row is tappable at all, its article is already in the Room cache — this screen has nothing left
 * to fetch on a miss, so [NotFound] is now the one and only "not in the cache" outcome.
 */
sealed interface ArticleDetailUiState {
    data object Loading : ArticleDetailUiState
    data class Content(val article: Article) : ArticleDetailUiState
    data object NotFound : ArticleDetailUiState
}

/**
 * Loads a single article by [articleId] from [repository]'s Room cache — the same cache
 * [com.neverlate.data.articles.ArticlesRemoteMediator] fills as the Articles list is paged (see
 * the feature 13c spec's *Detail screen coupling* risk: this screen is only reachable by tapping
 * an already-visible, already-cached row, so a genuine miss here is not expected in normal use).
 *
 * Feature 13d: [articleId] now arrives via [SavedStateHandle] instead of a plain constructor
 * parameter built by the retired `AppViewModelFactory` — the idiomatic way a Hilt `ViewModel`
 * reads a navigation argument, since `hiltViewModel()` (called from [ArticleDetailRoute]) gives
 * every injected ViewModel a [SavedStateHandle] backed by the current `NavBackStackEntry`, with no
 * extra wiring at the call site as long as the route declares the matching `navArgument` (see
 * `AppNavHost`'s `Routes.ARTICLE_DETAIL` composable). A missing `articleId` here is a *programmer
 * error* (every article screen needs one) — [requireNotNull] throws immediately, the same
 * "require, don't silently default" contract the retired `AppViewModelFactory` used to enforce via
 * its own `requireArticleId()`. Contrast with [com.neverlate.ui.tasks.TaskEditViewModel], where a
 * missing `taskId` is a normal, expected value instead.
 */
@HiltViewModel
class ArticleDetailViewModel @Inject constructor(
    private val repository: ArticleRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val articleId: String =
        requireNotNull(savedStateHandle.get<String>(ARG_ARTICLE_ID)) { "ArticleDetailViewModel requires an articleId" }

    private val _uiState = MutableStateFlow<ArticleDetailUiState>(ArticleDetailUiState.Loading)
    val uiState: StateFlow<ArticleDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val cached = repository.getArticleById(articleId)
            _uiState.value = if (cached != null) {
                ArticleDetailUiState.Content(cached)
            } else {
                ArticleDetailUiState.NotFound
            }
        }
    }
}
