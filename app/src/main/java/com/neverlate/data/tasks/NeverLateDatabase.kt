package com.neverlate.data.tasks

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.neverlate.data.articles.ArticleDao
import com.neverlate.data.articles.ArticleEntity
import com.neverlate.data.sync.Converters
import com.neverlate.data.sync.OutboxDao
import com.neverlate.data.sync.OutboxEntity

/**
 * The app's single Room database. `@Database` is the annotation that ties an entity list
 * ([Task], [ArticleEntity], [OutboxEntity]) to a set of DAOs ([TaskDao], [ArticleDao],
 * [OutboxDao]) and generates the actual SQLite-backed implementation of this abstract class via
 * KSP.
 *
 * [Task] was the only entity here through feature 09; feature 10 added [ArticleEntity] (the
 * offline cache behind [com.neverlate.data.articles.CachingArticleRepository]) as this database's
 * second table (`version = 2`). Feature 11 (remote DB + offline-first sync) bumps it to
 * `version = 3`: [Task] gains sync metadata (see its KDoc) and [OutboxEntity] adds the pending
 * change queue — see `docs/specs/2026-07-06-remote-db-sync.md`'s *Sync Model*.
 *
 * `@TypeConverters(Converters::class)` registers this project's first Room `@TypeConverter`s
 * (see [Converters]): Room has no built-in column type for the enums [SyncState] and
 * [com.neverlate.data.sync.OutboxOperation], so [Converters] tells it how to store/read them as
 * plain text instead.
 *
 * `exportSchema = false` opts out of Room writing a JSON schema-history file on every build (its
 * usual purpose is powering *real* Migration tests). Since this database currently only ever
 * falls back to destroying and recreating itself (see [fallbackToDestructiveMigration] below),
 * there is no migration history to check that file against yet; revisit this once a real
 * Migration is written.
 */
@Database(entities = [Task::class, ArticleEntity::class, OutboxEntity::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class NeverLateDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    abstract fun articleDao(): ArticleDao

    abstract fun outboxDao(): OutboxDao

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
                    // from here on must ship a real Migration instead. Feature 10's version 1 -> 2
                    // bump already relies on this: it wipes any tasks present on an existing
                    // device, which is accepted for the same pre-release reason (see the
                    // feature's tutorial lesson). Feature 11's 2 -> 3 bump (OQ-3, approved) keeps
                    // relying on it too — doubly acceptable here, since tasks become backend-owned
                    // in this feature: the local cache simply repopulates from the server on the
                    // next login, so wiping it on upgrade loses nothing durable.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
