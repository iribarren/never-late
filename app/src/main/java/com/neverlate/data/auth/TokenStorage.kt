package com.neverlate.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Reads and writes the current session (JWT + the user it belongs to).
 *
 * Declared as an interface — same reasoning as every other repository in this project — so
 * [AuthRepository] can be unit-tested against a simple in-memory fake, and so
 * [com.neverlate.data.sync.SyncEngine]/[com.neverlate.data.sync.AuthInterceptor] only need to know
 * "is there a token, and what is it", not how it is stored.
 */
interface TokenStorage {
    /** The current session's JWT, or null when logged out. */
    fun getToken(): String?

    /** The current session's user id, or null when logged out. */
    fun getUserId(): Long?

    /** The current session's user email, or null when logged out. */
    fun getUserEmail(): String?

    /** Persists a new session, replacing any previous one. */
    fun saveSession(token: String, userId: Long, email: String)

    /** Clears the session (logout, or a 401 from any backend call). */
    fun clearSession()
}

/**
 * Real implementation, backed by Jetpack Security's [EncryptedSharedPreferences] — a
 * `SharedPreferences` file whose keys *and* values are encrypted with a key that never leaves the
 * Android Keystore.
 *
 * This is deliberately **not** the plaintext DataStore
 * ([com.neverlate.data.DataStoreUserPreferencesRepository]) that already holds theme/reminder
 * preferences: those are not secrets, but a JWT is a bearer credential — anyone who reads it can
 * act as this user against the backend, so it needs Keystore-backed encryption at rest (US-2 of
 * the feature spec), not a plain XML file another app with root/a backup exploit could read.
 */
class EncryptedTokenStorage(context: Context) : TokenStorage {

    // Lazy: building the MasterKey touches the Keystore, which is unnecessary work for callers
    // that only ever call getToken() and find there is none (e.g. a cold app start before login).
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    override fun getUserId(): Long? = prefs.getLong(KEY_USER_ID, NO_USER_ID).takeIf { it != NO_USER_ID }

    override fun getUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)

    override fun saveSession(token: String, userId: Long, email: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_USER_ID, userId)
            .putString(KEY_USER_EMAIL, email)
            .apply()
    }

    override fun clearSession() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_FILE_NAME = "auth_secure_prefs"
        const val KEY_TOKEN = "token"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_EMAIL = "user_email"
        const val NO_USER_ID = -1L
    }
}
