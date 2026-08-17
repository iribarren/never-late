package com.neverlate.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests [DataStoreUserPreferencesRepository]'s `dynamicColor` plumbing (feature 16) against a
 * real, Robolectric-backed Preferences DataStore, rather than the in-memory
 * `FakeUserPreferencesRepository` used by ViewModel tests (that fake bypasses DataStore entirely,
 * so it cannot exercise the tolerant `preferences[Keys.DYNAMIC_COLOR] ?: false` read this class
 * actually performs). This mirrors the existing pattern of driving a real Android-backed
 * persistence class through Robolectric (see `EncryptedPrefsRecoveryTest`,
 * `OutboxTaskRepositoryTest`) rather than inventing a new one.
 *
 * The `user_prefs` DataStore is created by a top-level `preferencesDataStore` property delegate
 * (see `UserPreferencesRepository.kt`), which is a **process-wide singleton**: once created, it
 * keeps reading/writing the same on-disk file for the rest of the JVM's life, regardless of which
 * `Context` instance is passed afterwards, and nothing resets it between test methods. **All**
 * assertions therefore live in a single test method, in this exact order — fresh-install default,
 * explicit `true`, back to `false`, then a check that it composes with an unrelated preference —
 * so nothing here depends on JUnit's unspecified method-execution order across two @Test methods
 * sharing that one singleton store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataStoreUserPreferencesRepositoryTest {

    @Test
    fun `dynamicColor is tolerant-read as false, round-trips through save, and composes with other preferences`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val repository = DataStoreUserPreferencesRepository(context)

        // Tolerant read (US-4): no key has ever been written for this preference, so the mapping
        // falls back to UserPreferences' own default (false) instead of throwing or reading null.
        assertEquals(false, repository.userPreferences.first().dynamicColor)

        repository.saveDynamicColor(true)
        assertEquals(true, repository.userPreferences.first().dynamicColor)

        repository.saveDynamicColor(false)
        assertEquals(false, repository.userPreferences.first().dynamicColor)

        // Writing an unrelated preference (themeMode) must not disturb dynamicColor, and vice
        // versa — both keys live in the same "user_prefs" file/edit transaction seam.
        repository.saveThemeMode(ThemeMode.DARK)
        repository.saveDynamicColor(true)
        val preferences = repository.userPreferences.first()
        assertEquals(ThemeMode.DARK, preferences.themeMode)
        assertEquals(true, preferences.dynamicColor)
    }

    /**
     * editable-profile-name spec (D4/AC-2): [DataStoreUserPreferencesRepository.saveName] must
     * write only `Keys.NAME`, never touching `Keys.ONBOARDED` — proven against a real DataStore
     * (not the in-memory `FakeUserPreferencesRepository`, which can't exercise the actual `edit {}`
     * key isolation) in both starting states of `onboarded`. Also guards the sibling contract:
     * [saveOnboarding] still writes both keys atomically in one `edit {}`.
     *
     * Uses a fresh, distinct instance of [RuntimeEnvironment.getApplication] context per assertion
     * step within this single method, following the same "one test method, exact order" discipline
     * documented on the class above, since the `user_prefs` DataStore is a process-wide singleton
     * shared with the other `@Test` method in this class.
     */
    @Test
    fun `saveName writes only the name and leaves onboarded untouched in both starting states`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val repository = DataStoreUserPreferencesRepository(context)

        // Starting state: onboarded == false (fresh install default).
        assertEquals(false, repository.userPreferences.first().onboarded)

        repository.saveName("Ada")
        var preferences = repository.userPreferences.first()
        assertEquals("Ada", preferences.name)
        assertEquals(
            "saveName must not flip onboarded from its false starting state",
            false,
            preferences.onboarded,
        )

        // Now flip onboarded to true via saveOnboarding, and prove saveName leaves it there too.
        repository.saveOnboarding("Bea")
        preferences = repository.userPreferences.first()
        assertEquals("Bea", preferences.name)
        assertEquals(true, preferences.onboarded)

        repository.saveName("  Cova  ")
        preferences = repository.userPreferences.first()
        assertEquals("saveName must trim the value it writes", "Cova", preferences.name)
        assertEquals(
            "saveName must not disturb onboarded once it is true",
            true,
            preferences.onboarded,
        )

        // Sibling contract (regression guard): saveOnboarding still writes both keys atomically —
        // a fresh name AND onboarded = true in one edit{}, even starting from onboarded == true.
        repository.saveOnboarding("  Deva  ")
        preferences = repository.userPreferences.first()
        assertEquals("Deva", preferences.name)
        assertEquals(true, preferences.onboarded)
    }
}
