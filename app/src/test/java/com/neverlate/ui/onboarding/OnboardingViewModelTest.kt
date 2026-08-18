package com.neverlate.ui.onboarding

import com.neverlate.data.FakeUserPreferencesRepository
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

// FakeUserPreferencesRepository is the shared fake at com.neverlate.data.FakeUserPreferencesRepository
// (D12 of docs/specs/2026-08-18-focus-mode-shielding.md) — savedNames tracks saveOnboarding calls,
// savedNamesViaSaveName tracks saveName calls, matching the naming this file established.

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

    /**
     * editable-profile-name spec (AC-4/D4): [OnboardingViewModel.save] must keep calling
     * [saveOnboarding] — never [com.neverlate.data.UserPreferencesRepository.saveName], which
     * deliberately leaves `onboarded` untouched and would strand a first-run user on Tasks with no
     * `onboarded` flag ever set. Worth its own explicit assertion per the spec, not just an
     * incidental byproduct of the round-trip test above.
     */
    @Test
    fun `save never calls saveName, only saveOnboarding`() = runTest {
        viewModel.onNameChange("Ada")

        viewModel.save {}
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("Ada"), repository.savedNames)
        assertTrue(
            "onboarding must never call saveName — that would leave onboarded unset",
            repository.savedNamesViaSaveName.isEmpty(),
        )
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
