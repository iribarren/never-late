package com.neverlate.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.ui.home.HomeViewModel
import com.neverlate.ui.onboarding.OnboardingViewModel

/**
 * Manual dependency injection (no Hilt/Dagger yet, on purpose — this is still an early lesson):
 * builds each screen's ViewModel with the [repository] it needs.
 *
 * Compose's `viewModel(factory = ...)` helper calls [create] instead of a no-arg constructor,
 * which is required here because our ViewModels take constructor parameters.
 */
class AppViewModelFactory(private val repository: UserPreferencesRepository) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(OnboardingViewModel::class.java) ->
            OnboardingViewModel(repository) as T

        modelClass.isAssignableFrom(HomeViewModel::class.java) ->
            HomeViewModel(repository) as T

        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
