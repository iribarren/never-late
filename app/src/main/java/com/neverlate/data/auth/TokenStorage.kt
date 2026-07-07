package com.neverlate.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Reads and writes the current session: a short-lived **access** token (JWT, attached to every
 * `/tasks*` call) and, since feature 12, a long-lived **refresh** token used only to mint a new
 * access token via `POST /auth/refresh` (`docs/api/contract.md` §2.2) — see
 * [com.neverlate.data.sync.TokenAuthenticator].
 *
 * Declared as an interface — same reasoning as every other repository in this project — so
 * [AuthRepository] can be unit-tested against a simple in-memory fake, and so
 * [com.neverlate.data.sync.SyncEngine]/[com.neverlate.data.sync.AuthInterceptor]/
 * [com.neverlate.data.sync.TokenAuthenticator] only need to know "is there a token, and what is
 * it", not how it is stored.
 */
interface TokenStorage {
    /** The current session's access token (JWT), or null when logged out. */
    fun getAccessToken(): String?

    /** The current session's refresh token, or null when logged out. */
    fun getRefreshToken(): String?

    /** The current session's user id, or null when logged out. */
    fun getUserId(): Long?

    /** The current session's user email, or null when logged out. */
    fun getUserEmail(): String?

    /** Persists a brand-new session (register/login), replacing any previous one. */
    fun saveSession(accessToken: String, refreshToken: String, userId: Long, email: String)

    /**
     * Replaces **both** tokens together after a silent renewal (`POST /auth/refresh`), leaving
     * [getUserId]/[getUserEmail] untouched.
     *
     * NEW CONCEPT for this feature: **atomicity**. By the time this is called the server has
     * already rotated the refresh token (the one just presented is now dead server-side, per
     * contract §2.1), so a partial write here — e.g. the process dying after the new access token
     * lands but before the new refresh token does — would strand the session: the *next* renewal
     * would present a refresh token the server already invalidated, and be treated as reuse
     * (theft). Both values must land together, in one write.
     */
    fun saveTokens(accessToken: String, refreshToken: String)

    /** Clears the session (logout, or [com.neverlate.data.sync.TokenAuthenticator] giving up). */
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
class EncryptedTokenStorage(private val context: Context) : TokenStorage {

    // Lazy: building the MasterKey touches the Keystore, which is unnecessary work for callers
    // that only ever call getAccessToken() and find there is none (e.g. a cold app start before
    // login).
    //
    // Wrapped in [createEncryptedPrefsWithRecovery] because opening the file can fail: the
    // encrypted prefs are sealed with a hardware-bound Android Keystore key that never leaves the
    // device, so if the file and that key ever desync — e.g. the file is restored from a cloud
    // backup onto a fresh install/device whose Keystore has a *different* key, a known hazard on
    // some OEM ROMs — decryption throws AEADBadTagException. Without recovery that would crash the
    // app on every launch (a permanent boot loop) instead of just losing an un-recoverable session.
    private val prefs by lazy {
        createEncryptedPrefsWithRecovery(
            build = { buildEncryptedPrefs() },
            deleteCorruptState = { context.deleteSharedPreferences(PREFS_FILE_NAME) },
        )
    }

    private fun buildEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    override fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    override fun getUserId(): Long? = prefs.getLong(KEY_USER_ID, NO_USER_ID).takeIf { it != NO_USER_ID }

    override fun getUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)

    override fun saveSession(accessToken: String, refreshToken: String, userId: Long, email: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_USER_ID, userId)
            .putString(KEY_USER_EMAIL, email)
            .apply()
    }

    override fun saveTokens(accessToken: String, refreshToken: String) {
        // A single SharedPreferences.Editor batches every put() below into one atomic commit when
        // apply() is called — this is what "replaces both together" means in practice: a process
        // death before apply() leaves the *old* pair fully intact, never a mismatched mix of the
        // two, and apply() itself either lands both writes or (extremely rarely, on disk failure)
        // neither.
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    override fun clearSession() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_FILE_NAME = "auth_secure_prefs"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_EMAIL = "user_email"
        const val NO_USER_ID = -1L
    }
}

/**
 * Opens the encrypted prefs, recovering **once** from an un-decryptable keyset instead of letting
 * it crash the app.
 *
 * Pulled out as a pure function (no Android Keystore, no `Context`) so it can be unit-tested with
 * plain fakes: the real dependencies are passed in as [build] (create the `EncryptedSharedPreferences`)
 * and [deleteCorruptState] (delete the on-disk file so the next [build] starts clean).
 *
 * `EncryptedSharedPreferences.create` surfaces a Keystore desync as either a
 * [GeneralSecurityException] (the common case — `AEADBadTagException` is one) or an [IOException].
 * On either, we drop the corrupt file and rebuild **exactly once**: the second [build] regenerates a
 * fresh, empty keyset, so the user simply lands logged-out (session lost) rather than in a boot
 * loop. If that retry also fails the exception propagates — we never loop.
 */
internal fun createEncryptedPrefsWithRecovery(
    build: () -> SharedPreferences,
    deleteCorruptState: () -> Unit,
): SharedPreferences =
    try {
        build()
    } catch (e: GeneralSecurityException) {
        recoverCorruptPrefs(e, build, deleteCorruptState)
    } catch (e: IOException) {
        recoverCorruptPrefs(e, build, deleteCorruptState)
    }

private fun recoverCorruptPrefs(
    cause: Exception,
    build: () -> SharedPreferences,
    deleteCorruptState: () -> Unit,
): SharedPreferences {
    Log.w("EncryptedTokenStorage", "Encrypted token store unreadable; clearing and recreating", cause)
    deleteCorruptState()
    return build()
}
