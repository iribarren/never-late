package com.neverlate.data

import com.neverlate.domain.focus.FocusShieldOptions
import com.neverlate.domain.tasks.FocusSession
import com.neverlate.domain.tasks.TaskListCriteria
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The one shared in-memory [UserPreferencesRepository] fake for every JVM test in this module
 * (D12 of `docs/specs/2026-08-18-focus-mode-shielding.md`). Before this class, nine hand-written
 * near-duplicates existed across `test/`/`androidTest/` — some fully functional, some no-op on a
 * setter their own test never exercised, each tracking a different subset of calls under a
 * different property name. Growing that count a fourth consecutive feature in a row (see the
 * spec's D12 for the file-by-file history) was the trigger to stop deferring the consolidation.
 *
 * Every setter here is **fully functional** — it mutates the backing [userPreferences]
 * [MutableStateFlow] exactly the way [DataStoreUserPreferencesRepository]'s real `edit {}` would
 * — **and** records every call it received, in order, in its own `savedX` list, so a test can
 * assert both the resulting state and the exact sequence of writes (needed by, e.g., the
 * write-ahead-receipt ordering tests in `FocusShieldStartSequenceTest`).
 *
 * The `androidTest/` twin lives at
 * `app/src/androidTest/java/com/neverlate/data/FakeUserPreferencesRepository.kt` — a separate,
 * near-identical file because `test/` and `androidTest/` are different Gradle source sets that
 * cannot share code without a new module or `testFixtures` wiring (out of scope; see
 * `docs/diferidos.md`).
 */
class FakeUserPreferencesRepository(
    initial: UserPreferences = UserPreferences(),
) : UserPreferencesRepository {

    override val userPreferences = MutableStateFlow(initial)

    /** Every (untrimmed) name argument passed to [saveOnboarding], in call order. */
    val savedNames = mutableListOf<String>()

    /** Every (untrimmed) name argument passed to [saveName], in call order — kept separate from
     *  [savedNames] so a test can assert a rename never (re-)touches onboarding (D4 of the
     *  editable-profile-name spec: [saveName] must never also flip `onboarded`). */
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

    /** Every filter (or `null`) passed to [saveFocusShieldPriorFilter], in call order — a test can
     *  assert both the exact sequence and, via `.last()`, the final value. */
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
