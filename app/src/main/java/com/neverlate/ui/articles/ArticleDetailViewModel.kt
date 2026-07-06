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
 * Everything the Article detail screen needs to render itself.
 *
 * [Error] is new in feature 10, alongside the pre-existing [NotFound]: the two look similar (both
 * end up rendering a "there is nothing to show" message) but mean different things. [NotFound]
 * means the cache was reached successfully and simply has no article under this id — a normal,
 * expected outcome (e.g. a stale deep link to an article that no longer exists). [Error] means an
 * attempt to refresh the cache from the network failed while trying to resolve that same
 * situation, so the app cannot yet tell whether the id is genuinely invalid or the catalog is
 * just temporarily unreachable — see [ArticleDetailViewModel]'s KDoc for exactly when each is used.
 */
sealed interface ArticleDetailUiState {
    data object Loading : ArticleDetailUiState
    data class Content(val article: Article) : ArticleDetailUiState
    data object NotFound : ArticleDetailUiState
    data object Error : ArticleDetailUiState
}

/**
 * Loads a single article by [articleId] from [repository]'s cache, falling back to a network
 * [ArticleRepository.refresh] when the id isn't cached yet.
 *
 * [articleId] arrives here as a plain constructor parameter, built by
 * [com.neverlate.ui.navigation.AppViewModelFactory] from the navigation argument read in
 * `AppNavHost`. AndroidX's more idiomatic way to hand a navigation argument to a ViewModel is
 * `SavedStateHandle`, but every other ViewModel in this project already receives its
 * dependencies as explicit constructor parameters through manual DI (see
 * [com.neverlate.ui.onboarding.OnboardingViewModel], [com.neverlate.ui.home.HomeViewModel]);
 * reusing that same familiar pattern here keeps this lesson focused on one new concept
 * (navigation with arguments) instead of introducing a second DI mechanism at the same time.
 *
 * Since feature 10, a cache miss is no longer automatically "not found": the article might simply
 * not have been downloaded yet on this device (e.g. the list screen was never opened, or its
 * first refresh failed). So [load] only settles on [NotFound] after *also* trying
 * [ArticleRepository.refresh] and still not finding [articleId] — and settles on [Error] instead
 * if that refresh attempt itself failed, since in that case "not found" would be misleading (the
 * article might well exist, this device just couldn't confirm it).
 */
class ArticleDetailViewModel(
    private val repository: ArticleRepository,
    private val articleId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ArticleDetailUiState>(ArticleDetailUiState.Loading)
    val uiState: StateFlow<ArticleDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** Re-attempts the same cache-then-refresh sequence as [init]. Bound to the Error state's retry button. */
    fun retry() {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = ArticleDetailUiState.Loading

            val cached = repository.getArticleById(articleId)
            if (cached != null) {
                _uiState.value = ArticleDetailUiState.Content(cached)
                return@launch
            }

            // Not cached (yet): try to fetch the catalog before giving up on this id.
            val result = repository.refresh()
            val refreshed = repository.getArticleById(articleId)

            _uiState.value = when {
                refreshed != null -> ArticleDetailUiState.Content(refreshed)
                result is RefreshResult.Error -> ArticleDetailUiState.Error
                else -> ArticleDetailUiState.NotFound
            }
        }
    }
}
