package com.neverlate.data

import com.neverlate.domain.focus.FocusShieldOptions
import com.neverlate.domain.tasks.FocusSession
import com.neverlate.domain.tasks.TaskListCriteria
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The `androidTest/` twin of `app/src/test/java/com/neverlate/data/FakeUserPreferencesRepository.kt`
 * — see that file's KDoc for the full rationale (D12 of
 * `docs/specs/2026-08-18-focus-mode-shielding.md`). Kept as a **separate** file, not a shared one,
 * because `test/` and `androidTest/` are different Gradle source sets with no shared compilation
 * unit without a new module or `testFixtures` wiring (out of scope; see `docs/diferidos.md`).
 *
 * Consolidates three previously hand-written instrumented fakes
 * (`TasksEmptyStatePersonalizationTest.MutableUserPreferencesRepository`,
 * `TasksRouteSnackbarTest.NoopUserPreferencesRepository`,
 * `FocusTestDoubles.FakeFocusUserPreferencesRepository`) into one, every setter fully functional
 * (mutates the backing [userPreferences] [MutableStateFlow]) with call-order tracking lists, same
 * shape as the `test/` twin.
 */
class FakeUserPreferencesRepository(
    initial: UserPreferences = UserPreferences(),
) : UserPreferencesRepository {

    override val userPreferences = MutableStateFlow(initial)

    /** Every (untrimmed) name argument passed to [saveOnboarding], in call order. */
    val savedNames = mutableListOf<String>()

    /** Every (untrimmed) name argument passed to [saveName], in call order. */
    val savedNamesViaSaveName = mutableListOf<String>()

    /** Every [ThemeMode] passed to [saveThemeMode], in call order. */
    val savedThemeModes = mutableListOf<ThemeMode>()

    /** Every on/off value passed to [saveRemindersEnabled], in call order. */
    val savedRemindersEnabled = mutableListOf<Boolean>()

    /** Every lead-time (minutes) value passed to [saveReminderLeadMinutes], in call order. */
    val savedReminderLeadMinutes = mutableListOf<Int>()

    /** Every cursor value passed to [saveSyncCursor], in call order. */
    val savedSyncCursors = mutableListOf<Long>()

    /** Every on/off value passed to [saveDynamicColor], in call order. */
    val savedDynamicColor = mutableListOf<Boolean>()

    /** Every [TaskListCriteria] passed to [saveTaskListArrangement], in call order. */
    val savedTaskListArrangements = mutableListOf<TaskListCriteria>()

    /** Every [FocusSession] passed to [startFocusSession], in call order. */
    val startedFocusSessions = mutableListOf<FocusSession>()

    /** How many times [endFocusSession] was called. */
    var endFocusSessionCallCount = 0
        private set

    /** Every [FocusShieldOptions] passed to [saveFocusShieldOptions], in call order. */
    val savedFocusShieldOptions = mutableListOf<FocusShieldOptions>()

    /** Every filter (or `null`) passed to [saveFocusShieldPriorFilter], in call order. */
    val savedFocusShieldPriorFilters = mutableListOf<Int?>()

    override suspend fun saveOnboarding(name: String) {
        savedNames.add(name)
        userPreferences.value = userPreferences.value.copy(name = name.trim(), onboarded = true)
    }

    override suspend fun saveName(name: String) {
        savedNamesViaSaveName.add(name)
        userPreferences.value = userPreferences.value.copy(name = name.trim())
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
        savedSyncCursors.add(cursor)
        userPreferences.value = userPreferences.value.copy(syncCursor = cursor)
    }

    override suspend fun saveDynamicColor(enabled: Boolean) {
        savedDynamicColor.add(enabled)
        userPreferences.value = userPreferences.value.copy(dynamicColor = enabled)
    }

    override suspend fun saveTaskListArrangement(criteria: TaskListCriteria) {
        savedTaskListArrangements.add(criteria)
        userPreferences.value = userPreferences.value.copy(taskListArrangement = criteria)
    }

    override suspend fun startFocusSession(session: FocusSession) {
        startedFocusSessions.add(session)
        userPreferences.value = userPreferences.value.copy(focusSession = session)
    }

    override suspend fun endFocusSession() {
        endFocusSessionCallCount++
        userPreferences.value = userPreferences.value.copy(focusSession = null)
    }

    override suspend fun saveFocusShieldOptions(options: FocusShieldOptions) {
        savedFocusShieldOptions.add(options)
        userPreferences.value = userPreferences.value.copy(focusShieldOptions = options)
    }

    override suspend fun saveFocusShieldPriorFilter(filter: Int?) {
        savedFocusShieldPriorFilters.add(filter)
        userPreferences.value = userPreferences.value.copy(focusShieldPriorFilter = filter)
    }
}
