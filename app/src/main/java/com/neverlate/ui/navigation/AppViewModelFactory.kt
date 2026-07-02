package com.neverlate.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.articles.ArticleRepository
import com.neverlate.ui.articles.ArticleDetailViewModel
import com.neverlate.ui.articles.ArticlesViewModel
import com.neverlate.ui.home.HomeViewModel
import com.neverlate.ui.onboarding.OnboardingViewModel

/**
 * Manual dependency injection (no Hilt/Dagger yet, on purpose — this is still an early lesson):
 * builds each screen's ViewModel with the repository (or repositories) it needs.
 *
 * [articleRepository] and [articleId] are only used by the two Articles ViewModels, so they
 * default to null: Onboarding and Home keep constructing this factory with just a
 * [userPreferencesRepository], exactly as before this feature. `articleId` in particular is how
 * the navigation argument reaches [ArticleDetailViewModel] — see the explanation on that class.
 *
 * Compose's `viewModel(factory = ...)` helper calls [create] instead of a no-arg constructor,
 * which is required here because our ViewModels take constructor parameters.
 */
class AppViewModelFactory(
    private val userPreferencesRepository: UserPreferencesRepository? = null,
    private val articleRepository: ArticleRepository? = null,
    private val articleId: String? = null,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(OnboardingViewModel::class.java) ->
            OnboardingViewModel(requireUserPreferencesRepository()) as T

        modelClass.isAssignableFrom(HomeViewModel::class.java) ->
            HomeViewModel(requireUserPreferencesRepository()) as T

        modelClass.isAssignableFrom(ArticlesViewModel::class.java) ->
            ArticlesViewModel(requireArticleRepository()) as T

        modelClass.isAssignableFrom(ArticleDetailViewModel::class.java) ->
            ArticleDetailViewModel(requireArticleRepository(), requireArticleId()) as T

        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }

    private fun requireUserPreferencesRepository() =
        requireNotNull(userPreferencesRepository) { "This ViewModel requires a UserPreferencesRepository" }

    private fun requireArticleRepository() =
        requireNotNull(articleRepository) { "This ViewModel requires an ArticleRepository" }

    private fun requireArticleId() =
        requireNotNull(articleId) { "ArticleDetailViewModel requires an articleId" }
}
