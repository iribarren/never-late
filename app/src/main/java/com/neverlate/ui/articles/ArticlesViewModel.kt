package com.neverlate.ui.articles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.articles.Article
import com.neverlate.data.articles.ArticleRepository
import com.neverlate.data.articles.RefreshResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything the Articles list screen needs to render itself.
 *
 * Modeled as a sealed hierarchy (loading/content/empty/error) instead of a single "list that
 * might be empty" field, so the screen can tell "still loading" apart from "loaded, but there
 * really is nothing to show" apart from "the load itself failed" — and render a distinct message
 * (and, for [Error], a retry action) for each.
 *
 * [Error] is new in feature 10: feature 03's local, bundled JSON could never actually fail to
 * load, but a network call can (no connectivity, server down, malformed response...). It carries
 * no payload — the screen shows a fixed, translatable message plus a retry button, not the raw
 * exception — because [ArticlesViewModel] only reaches this state once it already knows there is
 * *no* cached content to fall back on (see the class KDoc below).
 */
sealed interface ArticlesUiState {
    data object Loading : ArticlesUiState
    data class Content(val articles: List<Article>) : ArticlesUiState
    data object Empty : ArticlesUiState
    data object Error : ArticlesUiState
}

/**
 * Drives the Articles list screen with a **stale-while-revalidate** strategy: show whatever is
 * already cached immediately (even if it might be outdated), then try to fetch fresh data in the
 * background and update the screen again once that finishes.
 *
 * This is a meaningful change from feature 03, where [ArticleRepository.getArticles] was the
 * *only* data source and a single load was the whole lifecycle. Since feature 10,
 * [ArticleRepository] is backed by a local cache that a remote [ArticleRepository.refresh] keeps
 * up to date (see `CachingArticleRepository`), so there are now two questions to answer on every
 * load, not one: "what does the cache currently say?" and "did refreshing it just now work?".
 * [loadThenRefresh] answers both, in that order, and both [init] and [refresh] call it — they are
 * the same operation, just triggered at two different moments (screen open vs. pull-to-refresh).
 *
 * [isRefreshing] is a second, independent piece of state (not folded into [ArticlesUiState])
 * because it answers a different question than [uiState] does: "is a network call in flight right
 * now?" rather than "what should the screen's main content area show?". Material 3's
 * `PullToRefreshBox` (see [ArticlesScreen]) expects exactly this shape — a boolean it can use to
 * drive its spinner — decoupled from whatever the rest of the screen is doing.
 */
class ArticlesViewModel(private val repository: ArticleRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<ArticlesUiState>(ArticlesUiState.Loading)
    val uiState: StateFlow<ArticlesUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadThenRefresh()
    }

    /**
     * Re-runs the same cache-then-refresh sequence as [init]. Bound to both the Error state's
     * retry button and the list's pull-to-refresh gesture (see [ArticlesRoute]) — from this
     * ViewModel's point of view those two triggers ask for exactly the same thing.
     */
    fun refresh() {
        loadThenRefresh()
    }

    private fun loadThenRefresh() {
        viewModelScope.launch {
            // Step 1 — show whatever is already cached, right away, with no network involved.
            val cached = repository.getArticles()
            _uiState.value = if (cached.isNotEmpty()) {
                ArticlesUiState.Content(cached)
            } else {
                ArticlesUiState.Loading
            }

            // Step 2 — attempt to bring the cache up to date. isRefreshing toggles around this
            // call only (not the cache reads before/after it), since that is the part
            // PullToRefreshBox's spinner represents.
            _isRefreshing.value = true
            val result = repository.refresh()
            _isRefreshing.value = false

            // Step 3 — re-read the cache: a successful refresh may have changed it, and even a
            // failed one might still find something Step 1 didn't (e.g. this is a retry after an
            // Error state, where Step 1's cache was empty by definition).
            val fresh = repository.getArticles()
            _uiState.value = when {
                fresh.isNotEmpty() -> ArticlesUiState.Content(fresh)
                result is RefreshResult.Error -> ArticlesUiState.Error
                else -> ArticlesUiState.Empty
            }
        }
    }
}
