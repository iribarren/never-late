package com.neverlate.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.articles.ArticleRepository
import com.neverlate.data.auth.AuthRepository
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.ui.articles.ArticleDetailViewModel
import com.neverlate.ui.articles.ArticlesViewModel
import com.neverlate.ui.auth.LoginViewModel
import com.neverlate.ui.auth.RegisterViewModel
import com.neverlate.ui.notification.ReminderScheduler
import com.neverlate.ui.onboarding.OnboardingViewModel
import com.neverlate.ui.settings.SettingsViewModel
import com.neverlate.ui.tasks.TaskEditViewModel
import com.neverlate.ui.tasks.TasksViewModel

/**
 * Manual dependency injection (no Hilt/Dagger yet, on purpose — this is still an early lesson):
 * builds each screen's ViewModel with the repository (or repositories) it needs.
 *
 * [articleRepository]/[articleId] and [taskRepository]/[taskId] are only used by their own
 * feature's ViewModels, so they all default to null: Onboarding keeps constructing this factory
 * with just a [userPreferencesRepository], exactly as before this feature. `articleId` and
 * `taskId` are how a navigation argument reaches a detail/edit ViewModel — see
 * [ArticleDetailViewModel] and [TaskEditViewModel].
 *
 * Note the difference between the two: a missing [articleId] is a programmer error (every
 * article screen needs one), so it goes through [requireArticleId] and throws. A missing
 * [taskId], on the other hand, is a normal, expected value — see [TaskEditViewModel]'s KDoc — so
 * it is passed straight through to the ViewModel without a `require*` check.
 *
 * Compose's `viewModel(factory = ...)` helper calls [create] instead of a no-arg constructor,
 * which is required here because our ViewModels take constructor parameters.
 *
 * [reminderScheduler] follows [taskRepository]'s example: only [SettingsViewModel] needs it (to
 * cancel every alarm when reminders are switched off, see its KDoc), so it defaults to null and
 * every other screen's factory call is unaffected.
 *
 * [authRepository] (feature 11) follows the same pattern again: [LoginViewModel]/[RegisterViewModel]
 * need it to register/log in, and [SettingsViewModel] needs it for its logout action.
 */
class AppViewModelFactory(
    private val userPreferencesRepository: UserPreferencesRepository? = null,
    private val articleRepository: ArticleRepository? = null,
    private val articleId: String? = null,
    private val taskRepository: TaskRepository? = null,
    private val taskId: Long? = null,
    private val reminderScheduler: ReminderScheduler? = null,
    private val authRepository: AuthRepository? = null,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(OnboardingViewModel::class.java) ->
            OnboardingViewModel(requireUserPreferencesRepository()) as T

        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(
                requireUserPreferencesRepository(),
                requireTaskRepository(),
                requireReminderScheduler(),
                requireAuthRepository(),
            ) as T

        modelClass.isAssignableFrom(ArticlesViewModel::class.java) ->
            ArticlesViewModel(requireArticleRepository()) as T

        modelClass.isAssignableFrom(ArticleDetailViewModel::class.java) ->
            ArticleDetailViewModel(requireArticleRepository(), requireArticleId()) as T

        modelClass.isAssignableFrom(TasksViewModel::class.java) ->
            TasksViewModel(requireTaskRepository()) as T

        modelClass.isAssignableFrom(TaskEditViewModel::class.java) ->
            TaskEditViewModel(requireTaskRepository(), taskId) as T

        modelClass.isAssignableFrom(LoginViewModel::class.java) ->
            LoginViewModel(requireAuthRepository()) as T

        modelClass.isAssignableFrom(RegisterViewModel::class.java) ->
            RegisterViewModel(requireAuthRepository()) as T

        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }

    private fun requireUserPreferencesRepository() =
        requireNotNull(userPreferencesRepository) { "This ViewModel requires a UserPreferencesRepository" }

    private fun requireArticleRepository() =
        requireNotNull(articleRepository) { "This ViewModel requires an ArticleRepository" }

    private fun requireArticleId() =
        requireNotNull(articleId) { "ArticleDetailViewModel requires an articleId" }

    private fun requireTaskRepository() =
        requireNotNull(taskRepository) { "This ViewModel requires a TaskRepository" }

    private fun requireReminderScheduler() =
        requireNotNull(reminderScheduler) { "This ViewModel requires a ReminderScheduler" }

    private fun requireAuthRepository() =
        requireNotNull(authRepository) { "This ViewModel requires an AuthRepository" }
}
