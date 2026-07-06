package com.neverlate.data.tasks

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.neverlate.data.articles.ArticleDao
import com.neverlate.data.articles.ArticleEntity

/**
 * The app's single Room database. `@Database` is the annotation that ties an entity list
 * ([Task], [ArticleEntity]) to a set of DAOs ([TaskDao], [ArticleDao]) and generates the actual
 * SQLite-backed implementation of this abstract class via KSP.
 *
 * [Task] was the only entity here through feature 09; feature 10 added [ArticleEntity] (the
 * offline cache behind [com.neverlate.data.articles.CachingArticleRepository]) as this database's
 * second table, which is why `version` is now `2` — remote sync (feature 11) is expected to
 * extend this schema further.
 *
 * `exportSchema = false` opts out of Room writing a JSON schema-history file on every build (its
 * usual purpose is powering *real* Migration tests). Since this database currently only ever
 * falls back to destroying and recreating itself (see [fallbackToDestructiveMigration] below),
 * there is no migration history to check that file against yet; revisit this once a real
 * Migration is written.
 */
@Database(entities = [Task::class, ArticleEntity::class], version = 2, exportSchema = false)
abstract class NeverLateDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    abstract fun articleDao(): ArticleDao

    companion object {
        private const val DATABASE_NAME = "never-late.db"

        // @Volatile makes writes to this field immediately visible to every thread, which is
        // what makes the double-checked locking below (check, lock, check again) safe: without
        // it, another thread could observe a partially-constructed database instance.
        @Volatile
        private var instance: NeverLateDatabase? = null

        /**
         * Returns the single, process-wide [NeverLateDatabase] instance, creating it on first
         * use. Every caller (currently just `MainActivity`) must go through this instead of
         * calling `Room.databaseBuilder` directly, so the whole app shares one connection.
         */
        fun getInstance(context: Context): NeverLateDatabase =
            instance ?: synchronized(this) {
                // Re-check inside the lock: another thread may have finished building the
                // database while this thread was waiting to enter the synchronized block.
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NeverLateDatabase::class.java,
                    DATABASE_NAME,
                )
                    // The app is still pre-release, so destroying and recreating the database on
                    // a schema change (rather than writing a Migration) is an acceptable
                    // shortcut for now. Once real users have data on-device, every schema change
                    // from here on (features 11+ are expected to touch this schema too) must ship
                    // a real Migration instead. Feature 10's version 1 -> 2 bump already relies on
                    // this: it wipes any tasks present on an existing device, which is accepted
                    // for the same pre-release reason (see the feature's tutorial lesson).
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
