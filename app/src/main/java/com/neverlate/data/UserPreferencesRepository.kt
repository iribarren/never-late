package com.neverlate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Top-level extension property that creates a single Preferences DataStore bound to this
 * [Context]. `preferencesDataStore` (a property delegate) takes care of creating the file on
 * disk and keeping one singleton instance per file name for the whole process.
 */
private val Context.userPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

/**
 * Immutable snapshot of everything we persist about the user so far.
 *
 * Defaults ([name] empty, [onboarded] false) are what a brand-new install reads before the
 * user has saved anything.
 */
data class UserPreferences(
    val name: String = "",
    val onboarded: Boolean = false,
)

/**
 * Reads and writes the user's onboarding data.
 *
 * Declared as an interface (rather than exposing the DataStore-backed class directly) so
 * ViewModels can be unit-tested against a simple in-memory fake, since DataStore itself needs
 * an Android runtime and is awkward to use from a plain JVM test.
 */
interface UserPreferencesRepository {
    /** Emits a new [UserPreferences] every time the underlying storage changes. */
    val userPreferences: Flow<UserPreferences>

    /** Persists the onboarding step: the (trimmed) name, and marks the user as onboarded. */
    suspend fun saveOnboarding(name: String)
}

/** Real implementation, backed by Jetpack DataStore (Preferences). */
class DataStoreUserPreferencesRepository(private val context: Context) : UserPreferencesRepository {

    // Grouping the DataStore keys keeps the string constants ("user_name", "onboarded") in one
    // place instead of scattered through the file.
    private object Keys {
        val NAME = stringPreferencesKey("user_name")
        val ONBOARDED = booleanPreferencesKey("onboarded")
    }

    override val userPreferences: Flow<UserPreferences> =
        context.userPrefsDataStore.data.map { preferences ->
            UserPreferences(
                name = preferences[Keys.NAME] ?: "",
                onboarded = preferences[Keys.ONBOARDED] ?: false,
            )
        }

    override suspend fun saveOnboarding(name: String) {
        // `edit` performs an atomic read-modify-write transaction against the DataStore file.
        context.userPrefsDataStore.edit { preferences ->
            preferences[Keys.NAME] = name.trim()
            preferences[Keys.ONBOARDED] = true
        }
    }
}
