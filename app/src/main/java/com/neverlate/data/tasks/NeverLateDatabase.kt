package com.neverlate.data.tasks

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
 * second table (`version = 2`). Feature 11 (remote DB + offline-first sync) bumped it to
 * `version = 3`: [Task] gains sync metadata (see its KDoc) and [OutboxEntity] adds the pending
 * change queue — see `docs/specs/2026-07-06-remote-db-sync.md`'s *Sync Model*.
 *
 * `@TypeConverters(Converters::class)` registers this project's first Room `@TypeConverter`s
 * (see [Converters]): Room has no built-in column type for the enums [SyncState] and
 * [com.neverlate.data.sync.OutboxOperation], so [Converters] tells it how to store/read them as
 * plain text instead.
 *
 * `exportSchema = false` opts out of Room writing a JSON schema-history file on every build (its
 * usual purpose is powering *real* Migration tests, e.g. [MIGRATION_3_4] below). Every prior
 * schema change (versions 1 -> 2 -> 3) relied on [fallbackToDestructiveMigration] instead — see
 * that call's KDoc for why that stops being acceptable from `version = 4` onward.
 */
@Database(entities = [Task::class, ArticleEntity::class, OutboxEntity::class], version = 4, exportSchema = false)
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
                    // The app was still pre-release through version 3, so destroying and
                    // recreating the database on a schema change (rather than writing a
                    // Migration) was an acceptable shortcut: Feature 10's version 1 -> 2 bump
                    // wiped any tasks present on an existing device (see that feature's tutorial
                    // lesson), and feature 11's 2 -> 3 bump (OQ-3, approved) relied on the same
                    // fallback again, doubly acceptable there since tasks became backend-owned —
                    // the local cache simply repopulated from the server on the next login.
                    //
                    // Feature 13 (guest mode) broke that assumption: a guest's tasks live *only*
                    // on-device (no account, sync inactive), so a destructive migration on upgrade
                    // would now permanently lose them. Feature 04c's 3 -> 4 bump therefore ships
                    // this project's first **real** Migration ([MIGRATION_3_4]) instead of falling
                    // back — [fallbackToDestructiveMigration] stays registered only as a safety net
                    // for schema versions Room has no explicit migration path for at all.
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }

        /**
         * A Room `Migration` describes, in raw SQL, how to bring one on-disk schema version up to
         * the next **without** losing existing rows — Room runs it automatically the first time it
         * opens a database still at the `startVersion` it declares. [MIGRATION_3_4] is additive and
         * trivial (a new nullable column has no existing data to reconcile): `tasks` gains
         * [Task.completedAt] (feature 04c), defaulting every existing row to `NULL` (still
         * pending) — exactly what a never-yet-completed task should read as after the upgrade.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN completedAt INTEGER")
            }
        }
    }
}
