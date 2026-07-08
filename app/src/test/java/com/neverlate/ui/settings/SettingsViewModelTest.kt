package com.neverlate.ui.settings

import com.neverlate.data.ThemeMode
import com.neverlate.data.UserPreferences
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.auth.AuthRepository
import com.neverlate.data.auth.AuthResult
import com.neverlate.data.auth.AuthState
import com.neverlate.data.tasks.Task
import com.neverlate.ui.notification.FakeReminderScheduler
import com.neverlate.ui.notification.FakeTaskRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Every dynamic-color on/off value this fake has been asked to save, in call order. */
    val savedDynamicColor = mutableListOf<Boolean>()

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

    override suspend fun saveSyncCursor(cursor: Long) {
        userPreferences.value = userPreferences.value.copy(syncCursor = cursor)
    }

    override suspend fun saveDynamicColor(enabled: Boolean) {
        savedDynamicColor.add(enabled)
        userPreferences.value = userPreferences.value.copy(dynamicColor = enabled)
    }
}

// FakeTaskRepository and FakeReminderScheduler are promoted to
// com.neverlate.ui.notification.ReminderTestDoubles.kt (imported above) since
// ReminderSchedulingRepositoryTest needs the exact same in-memory behaviour — see that file's
// doc comments for why each fake behaves the way it does.

/**
 * Minimal in-memory [AuthRepository] fake: only [logout] is exercised from this ViewModel, plus
 * [authState] itself (feature 13: [SettingsUiState.authState] now mirrors it, see its KDoc). The
 * [initialState] param lets tests start as [AuthState.Guest] instead of the default [AuthState.LoggedIn],
 * without needing a second fake class.
 */
private class FakeAuthRepository(
    initialState: AuthState = AuthState.LoggedIn(1, "ada@example.com"),
) : AuthRepository {
    private val _authState = MutableStateFlow(initialState)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    var logoutCallCount = 0
        private set

    override suspend fun register(email: String, password: String): AuthResult = AuthResult.Success

    override suspend fun login(email: String, password: String): AuthResult = AuthResult.Success

    override suspend fun logout() {
        logoutCallCount++
        // Feature 13 (PD-2): the real AuthRepositoryImpl.logout() lands in Guest, not LoggedOut —
        // mirror that here so this fake stays representative of production behaviour.
        _authState.value = AuthState.Guest
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeUserPreferencesRepository
    private lateinit var taskRepository: FakeTaskRepository
    private lateinit var reminderScheduler: FakeReminderScheduler
    private lateinit var authRepository: FakeAuthRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        taskRepository = FakeTaskRepository()
        reminderScheduler = FakeReminderScheduler()
        authRepository = FakeAuthRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state reflects the persisted theme mode`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences(themeMode = ThemeMode.DARK))
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler, authRepository)

        // The ViewModel collects the repository flow on viewModelScope; let that coroutine run.
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ThemeMode.DARK, viewModel.uiState.value.themeMode)
    }

    @Test
    fun `selecting a theme persists it and updates the state`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences(themeMode = ThemeMode.SYSTEM))
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler, authRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onThemeModeSelected(ThemeMode.LIGHT)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(ThemeMode.LIGHT), repository.savedThemeModes)
        assertEquals(ThemeMode.LIGHT, repository.userPreferences.value.themeMode)
        assertEquals(ThemeMode.LIGHT, viewModel.uiState.value.themeMode)
    }

    // dynamicColor / Material You toggle (feature 16) --------------------------------------------

    @Test
    fun `initial state reflects the persisted dynamicColor preference`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences(dynamicColor = true))
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler, authRepository)

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.dynamicColor)
    }

    @Test
    fun `initial state defaults dynamicColor to false when nothing was persisted`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences())
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler, authRepository)

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.dynamicColor)
    }

    @Test
    fun `enabling dynamic color persists it and updates the state`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences(dynamicColor = false))
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler, authRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onDynamicColorChanged(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(true), repository.savedDynamicColor)
        assertEquals(true, repository.userPreferences.value.dynamicColor)
        assertEquals(true, viewModel.uiState.value.dynamicColor)
    }

    @Test
    fun `disabling dynamic color persists it and updates the state`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences(dynamicColor = true))
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler, authRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onDynamicColorChanged(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(false), repository.savedDynamicColor)
        assertEquals(false, repository.userPreferences.value.dynamicColor)
        assertEquals(false, viewModel.uiState.value.dynamicColor)
    }

    @Test
    fun `dynamicColor composes alongside themeMode in the same uiState without clobbering it`() = runTest {
        // Two independent preferences feeding one uiState (see the ViewModel's init KDoc): changing
        // one via its own setter must not reset the other's already-observed value.
        repository = FakeUserPreferencesRepository(UserPreferences(themeMode = ThemeMode.DARK, dynamicColor = false))
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler, authRepository)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ThemeMode.DARK, viewModel.uiState.value.themeMode)
        assertEquals(false, viewModel.uiState.value.dynamicColor)

        viewModel.onDynamicColorChanged(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("changing dynamicColor must not disturb the already-observed themeMode", ThemeMode.DARK, viewModel.uiState.value.themeMode)
        assertEquals(true, viewModel.uiState.value.dynamicColor)

        viewModel.onThemeModeSelected(ThemeMode.LIGHT)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, viewModel.uiState.value.themeMode)
        assertEquals("changing themeMode must not disturb the already-observed dynamicColor", true, viewModel.uiState.value.dynamicColor)
    }

    @Test
    fun `disabling reminders cancels every task's reminder`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = true))
        taskRepository = FakeTaskRepository(listOf(Task(id = 1, title = "A"), Task(id = 2, title = "B")))
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler, authRepository)
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
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler, authRepository)
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
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler, authRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onRemindersEnabledChanged(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(7L), reminderScheduler.cancelledIds)
    }

    @Test
    fun `enabling reminders persists the switch but does not mass-cancel any task`() = runTest {
        repository = FakeUserPreferencesRepository(UserPreferences(remindersEnabled = false))
        taskRepository = FakeTaskRepository(listOf(Task(id = 1, title = "A"), Task(id = 2, title = "B")))
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler, authRepository)
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
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler, authRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onReminderLeadMinutesSelected(30)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(30), repository.savedReminderLeadMinutes)
        assertEquals(30, repository.userPreferences.value.reminderLeadMinutes)
        assertEquals(30, viewModel.uiState.value.reminderLeadMinutes)
    }

    @Test
    fun `logout delegates to the auth repository`() = runTest {
        repository = FakeUserPreferencesRepository()
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler, authRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.logout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, authRepository.logoutCallCount)
        // Feature 13 (PD-2): logout lands in Guest (a usable, empty local mode), not LoggedOut.
        assertEquals(AuthState.Guest, authRepository.authState.value)
    }

    // authState mirroring (feature 13) --------------------------------------------------------------

    @Test
    fun `uiState authState reflects LoggedIn when the auth repository starts LoggedIn`() = runTest {
        repository = FakeUserPreferencesRepository()
        authRepository = FakeAuthRepository(initialState = AuthState.LoggedIn(1, "ada@example.com"))
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler, authRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthState.LoggedIn(1, "ada@example.com"), viewModel.uiState.value.authState)
    }

    @Test
    fun `uiState authState reflects Guest when the auth repository starts as a guest`() = runTest {
        repository = FakeUserPreferencesRepository()
        authRepository = FakeAuthRepository(initialState = AuthState.Guest)
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler, authRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthState.Guest, viewModel.uiState.value.authState)
    }

    @Test
    fun `uiState authState flips to Guest after logout, reflecting the account section switching entries`() = runTest {
        repository = FakeUserPreferencesRepository()
        authRepository = FakeAuthRepository(initialState = AuthState.LoggedIn(1, "ada@example.com"))
        viewModel = SettingsViewModel(repository, taskRepository, reminderScheduler, authRepository)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(AuthState.LoggedIn(1, "ada@example.com"), viewModel.uiState.value.authState)

        viewModel.logout()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthState.Guest, viewModel.uiState.value.authState)
    }
}
