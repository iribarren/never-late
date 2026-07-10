package com.neverlate.ui.articles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.articles.Article
import com.neverlate.data.articles.ArticleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 * [articleId] arrives here as a plain constructor parameter, built by
 * [com.neverlate.ui.navigation.AppViewModelFactory] from the navigation argument read in
 * `AppNavHost`. AndroidX's more idiomatic way to hand a navigation argument to a ViewModel is
 * `SavedStateHandle`, but every other ViewModel in this project already receives its
 * dependencies as explicit constructor parameters through manual DI (see
 * [com.neverlate.ui.onboarding.OnboardingViewModel], [com.neverlate.ui.settings.SettingsViewModel]);
 * reusing that same familiar pattern here keeps this lesson focused on one new concept
 * (navigation with arguments) instead of introducing a second DI mechanism at the same time.
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
