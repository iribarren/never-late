package com.neverlate.ui.settings

import com.neverlate.data.ThemeMode
import com.neverlate.data.UserPreferences
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.tasks.Task
import com.neverlate.ui.notification.FakeReminderScheduler
import com.neverlate.ui.notification.FakeTaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** Every on/off value this fake has been asked to save, in call order. */
    val savedRemindersEnabled = mutableListOf<Boolean>()

    /** Every lead-time (minutes) value this fake has been asked to save, in call order. */
    val savedReminderLeadMinutes = mutableListOf<Int>()

    override suspend fun saveOnboarding(name: String) {
        userPreferences.value = userPreferences.value.copy(name = name.trim(), onboarded = true)
    }

    override suspend fun saveThemeMode(mode: ThemeMode) {
        savedThemeModes.add(mode)
        userPreferences.value = userPreferences.value.copy(themeMode = mode)
    }

    override suspend fun saveRemindersEnabled(enabled: Boolean) {
        savedRemindersEnabled.add(enabled)
        userPreferences.value = userPreferences.value.copy(remindersEnabled = enabled)
    }

    override suspend fun saveReminderLeadMinutes(minutes: Int) {
        savedReminderLeadMinutes.add(minutes)
        userPreferences.value = userPreferences.value.copy(reminderLeadMinutes = minutes)
    }
}

// FakeTaskRepository and FakeReminderScheduler are promoted to
// com.neverlate.ui.notification.ReminderTestDoubles.kt (imported above) since
// ReminderSchedulingRepositoryTest needs the exact same in-memory behaviour — see that file's
// doc comments for why each fake behaves the way it does.

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeUserPreferencesRepository
    private lateinit var taskRepository: FakeTaskRepository
    private lateinit var reminderScheduler: FakeReminderScheduler
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        taskRepository = FakeTaskRepository()
        reminderScheduler = FakeReminderScheduler()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state reflects the persisted theme mode`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences(themeMode = ThemeMode.DARK))
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler)

        // The ViewModel collects the repository flow on viewModelScope; let that coroutine run.
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ThemeMode.DARK, viewModel.uiState.value.themeMode)
    }

    @Test
    fun `selecting a theme persists it and updates the state`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences(themeMode = ThemeMode.SYSTEM))
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onThemeModeSelected(ThemeMode.LIGHT)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(ThemeMode.LIGHT), repository.savedThemeModes)
        assertEquals(ThemeMode.LIGHT, repository.userPreferences.value.themeMode)
        assertEquals(ThemeMode.LIGHT, viewModel.uiState.value.themeMode)
    }

    @Test
    fun `disabling reminders cancels every task's reminder`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = true))
        taskRepository = FakeTaskRepository(listOf(Task(id = 1, title = "A"), Task(id = 2, title = "B")))
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onRemindersEnabledChanged(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, repository.userPreferences.value.remindersEnabled)
        assertEquals(listOf(1L, 2L), reminderScheduler.cancelledIds)
    }

    @Test
    fun `disabling reminders with no tasks cancels nothing`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = true))
        taskRepository = FakeTaskRepository(emptyList())
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onRemindersEnabledChanged(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, repository.userPreferences.value.remindersEnabled)
        assertEquals(emptyList<Long>(), reminderScheduler.cancelledIds)
    }

    @Test
    fun `disabling reminders with a single task cancels exactly that task's reminder`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = true))
        taskRepository = FakeTaskRepository(listOf(Task(id = 7, title = "Solo una")))
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onRemindersEnabledChanged(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(7L), reminderScheduler.cancelledIds)
    }

    @Test
    fun `enabling reminders persists the switch but does not mass-cancel any task`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = false))
        taskRepository = FakeTaskRepository(listOf(Task(id = 1, title = "A"), Task(id = 2, title = "B")))
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onRemindersEnabledChanged(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, repository.userPreferences.value.remindersEnabled)
        assertEquals(listOf(true), repository.savedRemindersEnabled)
        assertEquals(
            "turning reminders ON must not cancel any existing alarm — only turning them off does",
            emptyList<Long>(),
            reminderScheduler.cancelledIds,
        )
        assertTrue(reminderScheduler.scheduledCalls.isEmpty())
    }

    @Test
    fun `selecting a lead time persists it via saveReminderLeadMinutes and updates the state`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences(reminderLeadMinutes = 10))
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onReminderLeadMinutesSelected(30)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(30), repository.savedReminderLeadMinutes)
        assertEquals(30, repository.userPreferences.value.reminderLeadMinutes)
        assertEquals(30, viewModel.uiState.value.reminderLeadMinutes)
    }
}
