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
 * Everything the Articles list screen needs to render itself.
 *
 * Modeled as a sealed hierarchy (loading/content/empty) instead of a single "list that might be
 * empty" field, so the screen can tell "still loading" apart from "loaded, but there really is
 * nothing to show" and render a distinct message for each (see the feature spec's empty-state
 * acceptance criterion).
 */
sealed interface ArticlesUiState {
    data object Loading : ArticlesUiState
    data class Content(val articles: List<Article>) : ArticlesUiState
    data object Empty : ArticlesUiState
}

/**
 * Loads the article catalog from [repository] once, on creation.
 *
 * Unlike [com.neverlate.ui.home.HomeViewModel] (which continuously observes a Flow of
 * preferences that can change at any time), this ViewModel does a single one-shot suspend load:
 * the bundled article catalog does not change while the app is running, so there is nothing to
 * keep observing.
 */
class ArticlesViewModel(private val repository: ArticleRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<ArticlesUiState>(ArticlesUiState.Loading)
    val uiState: StateFlow<ArticlesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val articles = repository.getArticles()
            _uiState.value = if (articles.isEmpty()) {
                ArticlesUiState.Empty
            } else {
                ArticlesUiState.Content(articles)
            }
        }
    }
}
