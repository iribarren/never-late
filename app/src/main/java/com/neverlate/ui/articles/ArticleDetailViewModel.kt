package com.neverlate.ui.articles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.articles.Article
import com.neverlate.data.articles.ArticleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Everything the Article detail screen needs to render itself. */
sealed interface ArticleDetailUiState {
    data object Loading : ArticleDetailUiState
    data class Content(val article: Article) : ArticleDetailUiState
    data object NotFound : ArticleDetailUiState
}

/**
 * Loads a single article by [articleId] from [repository].
 *
 * [articleId] arrives here as a plain constructor parameter, built by
 * [com.neverlate.ui.navigation.AppViewModelFactory] from the navigation argument read in
 * `AppNavHost`. AndroidX's more idiomatic way to hand a navigation argument to a ViewModel is
 * `SavedStateHandle`, but every other ViewModel in this project already receives its
 * dependencies as explicit constructor parameters through manual DI (see
 * [com.neverlate.ui.onboarding.OnboardingViewModel], [com.neverlate.ui.home.HomeViewModel]);
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
        viewModelScope.launch {
            val article = repository.getArticleById(articleId)
            _uiState.value = if (article != null) {
                ArticleDetailUiState.Content(article)
            } else {
                ArticleDetailUiState.NotFound
            }
        }
    }
}
