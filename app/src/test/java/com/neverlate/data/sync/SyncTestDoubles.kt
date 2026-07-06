package com.neverlate.data.sync

import android.app.Application
import androidx.room.Room
import com.neverlate.data.ThemeMode
import com.neverlate.data.UserPreferences
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.auth.TokenStorage
import com.neverlate.data.tasks.NeverLateDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.RuntimeEnvironment

/**
 * Builds a real, **in-memory** [NeverLateDatabase] for feature 11's sync tests. Unlike
 * `CachingArticleRepositoryTest` (feature 10), which could get away with a hand-written
 * [com.neverlate.data.articles.ArticleDao] fake, [OutboxTaskRepository]/[SyncEngine] call
 * `database.withTransaction { ... }` directly — an extension on the real `RoomDatabase` that
 * needs an actual (shadowed) SQLite connection underneath to provide real transactional
 * guarantees. Robolectric supplies that connection on the plain JVM, no emulator required; see the
 * `robolectric` entry in `gradle/libs.versions.toml` for why this dependency was added.
 */
fun buildInMemoryTestDatabase(): NeverLateDatabase {
    val context = RuntimeEnvironment.getApplication() as Application
    return Room.inMemoryDatabaseBuilder(context, NeverLateDatabase::class.java)
        .allowMainThreadQueries() // tests run coroutines on the (Robolectric) main thread via runTest
        .build()
}

/** In-memory [TokenStorage] fake — no Keystore/EncryptedSharedPreferences involved. */
class FakeTokenStorage(
    private var token: String? = null,
    private var userId: Long? = null,
    private var email: String? = null,
) : TokenStorage {
    /** Every session [saveSession] recorded, in call order. */
    val savedSessions = mutableListOf<Triple<String, Long, String>>()

    /** How many times [clearSession] was called. */
    var clearCount = 0
        private set

    override fun getToken(): String? = token

    override fun getUserId(): Long? = userId

    override fun getUserEmail(): String? = email

    override fun saveSession(token: String, userId: Long, email: String) {
        this.token = token
        this.userId = userId
        this.email = email
        savedSessions += Triple(token, userId, email)
    }

    override fun clearSession() {
        token = null
        userId = null
        email = null
        clearCount++
    }
}

/**
 * In-memory [UserPreferencesRepository] fake — same shape as the private one in
 * `ReminderSchedulingRepositoryTest`, duplicated here (rather than promoted/shared across
 * packages) since that one is `private` to its own file and this project's convention only
 * promotes a fake to a shared file when *two callers in the same package* need it (see
 * `ReminderTestDoubles.kt`'s own KDoc).
 */
class FakeUserPreferencesRepository(
    initial: UserPreferences = UserPreferences(),
) : UserPreferencesRepository {

    override val userPreferences = MutableStateFlow(initial)

    override suspend fun saveOnboarding(name: String) {}

    override suspend fun saveThemeMode(mode: ThemeMode) {}

    override suspend fun saveRemindersEnabled(enabled: Boolean) {
        userPreferences.value = userPreferences.value.copy(remindersEnabled = enabled)
    }

    override suspend fun saveReminderLeadMinutes(minutes: Int) {
        userPreferences.value = userPreferences.value.copy(reminderLeadMinutes = minutes)
    }

    override suspend fun saveSyncCursor(cursor: Long) {
        userPreferences.value = userPreferences.value.copy(syncCursor = cursor)
    }
}
