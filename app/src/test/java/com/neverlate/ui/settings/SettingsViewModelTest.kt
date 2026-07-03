package com.neverlate.ui.settings

import com.neverlate.data.ThemeMode
import com.neverlate.data.UserPreferences
import com.neverlate.data.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * In-memory fake for [UserPreferencesRepository]. Records every [saveThemeMode] call and updates
 * the exposed [userPreferences] flow, so tests can assert both what was persisted and that the
 * ViewModel re-observes it — without touching real DataStore (which needs an Android runtime).
 */
private class FakeUserPreferencesRepository(
    initial: UserPreferences = UserPreferences(),
) : UserPreferencesRepository {

    override val userPreferences = MutableStateFlow(initial)

    /** Every theme mode this fake has been asked to save, in call order. */
    val savedThemeModes = mutableListOf<ThemeMode>()

    override suspend fun saveOnboarding(name: String) {
        userPreferences.value = userPreferences.value.copy(name = name.trim(), onboarded = true)
    }

    override suspend fun saveThemeMode(mode: ThemeMode) {
        savedThemeModes.add(mode)
        userPreferences.value = userPreferences.value.copy(themeMode = mode)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeUserPreferencesRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state reflects the persisted theme mode`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences(themeMode = ThemeMode.DARK))
        viewModel = SettingsViewModel(repository)

        // The ViewModel collects the repository flow on viewModelScope; let that coroutine run.
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ThemeMode.DARK, viewModel.uiState.value.themeMode)
    }

    @Test
    fun `selecting a theme persists it and updates the state`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences(themeMode = ThemeMode.SYSTEM))
        viewModel = SettingsViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onThemeModeSelected(ThemeMode.LIGHT)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(ThemeMode.LIGHT), repository.savedThemeModes)
        assertEquals(ThemeMode.LIGHT, repository.userPreferences.value.themeMode)
        assertEquals(ThemeMode.LIGHT, viewModel.uiState.value.themeMode)
    }
}
