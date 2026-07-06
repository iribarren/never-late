package com.neverlate.ui.onboarding

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory fake for [UserPreferencesRepository]. Records every call to [saveOnboarding] so
 * tests can assert on exactly what the ViewModel persisted, without touching real DataStore
 * (which needs an Android runtime and would not run in a plain JVM unit test).
 */
private class FakeUserPreferencesRepository(
    initial: UserPreferences = UserPreferences(),
) : UserPreferencesRepository {

    override val userPreferences = MutableStateFlow(initial)

    /** Every (name) argument this fake has been asked to save, in call order. */
    val savedNames = mutableListOf<String>()

    override suspend fun saveOnboarding(name: String) {
        savedNames.add(name)
        userPreferences.value = userPreferences.value.copy(name = name.trim(), onboarded = true)
    }

    override suspend fun saveThemeMode(mode: com.neverlate.data.ThemeMode) {
        userPreferences.value = userPreferences.value.copy(themeMode = mode)
    }

    override suspend fun saveRemindersEnabled(enabled: Boolean) {
        userPreferences.value = userPreferences.value.copy(remindersEnabled = enabled)
    }

    override suspend fun saveReminderLeadMinutes(minutes: Int) {
        userPreferences.value = userPreferences.value.copy(reminderLeadMinutes = minutes)
    }

    override suspend fun saveSyncCursor(cursor: Long) {
        userPreferences.value = userPreferences.value.copy(syncCursor = cursor)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeUserPreferencesRepository
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        // viewModelScope uses Dispatchers.Main internally; StandardTestDispatcher + setMain lets
        // runTest control when that coroutine actually runs.
        Dispatchers.setMain(testDispatcher)
        repository = FakeUserPreferencesRepository()
        viewModel = OnboardingViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has blank name and save disabled`() {
        val state = viewModel.uiState.value

        assertEquals("", state.name)
        assertFalse(state.isSaveEnabled)
    }

    @Test
    fun `onNameChange with blank value keeps save disabled`() {
        viewModel.onNameChange("   ")

        val state = viewModel.uiState.value
        assertEquals("   ", state.name)
        assertFalse(state.isSaveEnabled)
    }

    @Test
    fun `onNameChange with non-blank value enables save`() {
        viewModel.onNameChange("Ada")

        val state = viewModel.uiState.value
        assertEquals("Ada", state.name)
        assertTrue(state.isSaveEnabled)
    }

    @Test
    fun `save persists trimmed name, marks onboarded, and invokes onSaved`() = runTest {
        viewModel.onNameChange("  Ada  ")

        var onSavedCalled = false
        viewModel.save { onSavedCalled = true }

        // The ViewModel's save() launches on viewModelScope (backed by the StandardTestDispatcher
        // set as Main); advance it so the coroutine actually completes before asserting.
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("  Ada  "), repository.savedNames)
        assertEquals("Ada", repository.userPreferences.value.name)
        assertTrue(repository.userPreferences.value.onboarded)
        assertTrue(onSavedCalled)
    }

    @Test
    fun `save with blank name does not call repository or onSaved`() = runTest {
        // Save button should be disabled in this state, but guard defensively against a direct
        // call anyway (e.g. a misbehaving caller).
        var onSavedCalled = false

        viewModel.save { onSavedCalled = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(repository.savedNames.isEmpty())
        assertFalse(onSavedCalled)
    }
}
