package com.neverlate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
 * The theme the user has chosen for the whole app.
 *
 * [SYSTEM] means "follow whatever the device is set to" (light/dark), so it is not a fixed
 * colour — the concrete decision is recomputed on every composition. This is why we persist the
 * three-state *mode* and not a plain light/dark boolean (see [com.neverlate.ui.theme.themeModeToDark]).
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM;

    companion object {
        /**
         * Turns the stored string back into a [ThemeMode], tolerating anything unexpected.
         *
         * We persist [ThemeMode] as its [name] (e.g. "DARK"). A value that is absent (`null`, a
         * fresh install) or unrecognised (e.g. an old/corrupted key, or a mode removed in a future
         * version) must never crash — it falls back to the safe default [SYSTEM].
         */
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/**
 * Immutable snapshot of everything we persist about the user so far.
 *
 * Defaults ([name] empty, [onboarded] false, [themeMode] SYSTEM) are what a brand-new install
 * reads before the user has saved anything. [remindersEnabled]/[reminderLeadMinutes] (feature 09)
 * default to **on, 10 minutes** — a fresh install starts sending reminders without the person
 * having to find a setting first (US-4).
 */
data class UserPreferences(
    val name: String = "",
    val onboarded: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val remindersEnabled: Boolean = true,
    val reminderLeadMinutes: Int = DEFAULT_REMINDER_LEAD_MINUTES,
    /**
     * The **pull** cursor (feature 11): the server time of the last successful sync, so the next
     * `GET /tasks?since=` only asks for what changed after it. `0` (the default) means "never
     * synced" — the contract treats a missing/zero `since` as "give me everything". Not sensitive,
     * so — per the spec — it lives in this same non-encrypted store rather than alongside the
     * auth token (see [com.neverlate.data.auth.TokenStorage]).
     */
    val syncCursor: Long = 0L,
    /**
     * Material You / dynamic color opt-in (feature 16). `false` (the default, both here and on a
     * fresh install with no key yet) means the app renders its own brand color scheme;
     * `true` hands color over to `dynamic{Light,Dark}ColorScheme`, which derives it from the
     * device wallpaper on Android 12+ (and has no effect below that, see
     * [com.neverlate.ui.theme.NeverLateTheme]). Defaulting to `false` is a deliberate product
     * decision: a new user sees "Never Late Again"'s own identity, not whatever their wallpaper
     * happens to produce, unless they opt in from Settings.
     */
    val dynamicColor: Boolean = false,
) {
    companion object {
        /** Default lead time (minutes) a reminder fires before a task's deadline. */
        const val DEFAULT_REMINDER_LEAD_MINUTES = 10
    }
}

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

    /** Persists the app-wide theme choice. */
    suspend fun saveThemeMode(mode: ThemeMode)

    /**
     * Persists the reminders on/off switch (feature 09, US-4). Turning reminders off does not, by
     * itself, cancel anything already scheduled — see
     * [com.neverlate.ui.settings.SettingsViewModel.onRemindersEnabledChanged] for that half of the
     * behaviour, which needs the task list and the scheduler, neither of which this repository
     * knows about.
     */
    suspend fun saveRemindersEnabled(enabled: Boolean)

    /**
     * Persists the default lead time (in minutes). OQ-3 (approved): this only affects reminders
     * scheduled from this point on — existing alarms already scheduled under the old lead time are
     * not retroactively rescheduled.
     */
    suspend fun saveReminderLeadMinutes(minutes: Int)

    /** Persists the sync pull cursor (feature 11) — see [UserPreferences.syncCursor]. */
    suspend fun saveSyncCursor(cursor: Long)

    /** Persists the Material You / dynamic color opt-in (feature 16) — see [UserPreferences.dynamicColor]. */
    suspend fun saveDynamicColor(enabled: Boolean)
}

/** Real implementation, backed by Jetpack DataStore (Preferences). */
class DataStoreUserPreferencesRepository(private val context: Context) : UserPreferencesRepository {

    // Grouping the DataStore keys keeps the string constants ("user_name", "onboarded") in one
    // place instead of scattered through the file.
    private object Keys {
        val NAME = stringPreferencesKey("user_name")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        // Added in feature 07. Stored in the same "user_prefs" file as the onboarding keys — this
        // repository extends the existing store rather than creating a second one.
        val THEME_MODE = stringPreferencesKey("theme_mode")
        // Added in feature 09 — same "user_prefs" file yet again, no second DataStore.
        val REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        val REMINDER_LEAD_MINUTES = intPreferencesKey("reminder_lead_minutes")
        // Added in feature 11 — same "user_prefs" file yet again, no second DataStore.
        val SYNC_CURSOR = longPreferencesKey("sync_cursor")
        // Added in feature 16 — same "user_prefs" file yet again, no second DataStore.
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }

    override val userPreferences: Flow<UserPreferences> =
        context.userPrefsDataStore.data.map { preferences ->
            UserPreferences(
                name = preferences[Keys.NAME] ?: "",
                onboarded = preferences[Keys.ONBOARDED] ?: false,
                // The enum is persisted as a String, so parsing goes through fromStorage, which
                // maps a missing/unknown value back to the SYSTEM default instead of throwing.
                themeMode = ThemeMode.fromStorage(preferences[Keys.THEME_MODE]),
                // A missing key (fresh install, or an install from before feature 09) falls back
                // to UserPreferences' own defaults — tolerant parsing, same as the two keys above.
                remindersEnabled = preferences[Keys.REMINDERS_ENABLED] ?: true,
                reminderLeadMinutes = preferences[Keys.REMINDER_LEAD_MINUTES]
                    ?: UserPreferences.DEFAULT_REMINDER_LEAD_MINUTES,
                syncCursor = preferences[Keys.SYNC_CURSOR] ?: 0L,
                // Missing key (fresh install, or an install from before feature 16) falls back to
                // UserPreferences' own default (false) — same tolerant-read pattern as every key
                // above.
                dynamicColor = preferences[Keys.DYNAMIC_COLOR] ?: false,
            )
        }

    override suspend fun saveOnboarding(name: String) {
        // `edit` performs an atomic read-modify-write transaction against the DataStore file.
        context.userPrefsDataStore.edit { preferences ->
            preferences[Keys.NAME] = name.trim()
            preferences[Keys.ONBOARDED] = true
        }
    }

    override suspend fun saveThemeMode(mode: ThemeMode) {
        context.userPrefsDataStore.edit { preferences ->
            preferences[Keys.THEME_MODE] = mode.name
        }
    }

    override suspend fun saveRemindersEnabled(enabled: Boolean) {
        context.userPrefsDataStore.edit { preferences ->
            preferences[Keys.REMINDERS_ENABLED] = enabled
        }
    }

    override suspend fun saveReminderLeadMinutes(minutes: Int) {
        context.userPrefsDataStore.edit { preferences ->
            preferences[Keys.REMINDER_LEAD_MINUTES] = minutes
        }
    }

    override suspend fun saveSyncCursor(cursor: Long) {
        context.userPrefsDataStore.edit { preferences ->
            preferences[Keys.SYNC_CURSOR] = cursor
        }
    }

    override suspend fun saveDynamicColor(enabled: Boolean) {
        context.userPrefsDataStore.edit { preferences ->
            preferences[Keys.DYNAMIC_COLOR] = enabled
        }
    }
}
