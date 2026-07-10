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
import com.neverlate.data.articles.ArticleRemoteKeys
import com.neverlate.data.articles.ArticleRemoteKeysDao
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
 * (see [Converters]): Room has no built-in column type for the enums [SyncState],
 * [com.neverlate.data.sync.OutboxOperation] and (feature 13b) [com.neverlate.data.tasks.Priority],
 * so [Converters] tells it how to store/read them as plain text instead.
 *
 * `exportSchema = true` (flipped from `false` in feature 13b) makes Room write a JSON snapshot of
 * each schema version under `app/schemas/` on every build. Those snapshots are what a *real*
 * migration test needs: `MigrationTestHelper` creates a database at the old version's schema and
 * runs the [Migration] up to the new one, so both `N.json` and `N+1.json` must exist to test the
 * `N -> N+1` jump. Because export was off through version 4, its baseline `4.json` was generated
 * one-off (build the module with export on but still at `version = 4`) and committed alongside
 * `5.json` — see `tutorial/13b-migraciones-room.md`. The schema output location is set via the
 * `room.schemaLocation` KSP argument in `app/build.gradle.kts`.
 *
 * Feature 13c bumps `version = 5 -> 6`: paginating Articles with Jetpack Paging 3 needs a second
 * table, [ArticleRemoteKeys] (Paging's per-article "which page did this come from" bookkeeping —
 * see its KDoc), and [ArticleEntity] itself gains an ordering column
 * ([ArticleEntity.remoteOrder]) so cached pages come back in the server's order instead of
 * SQLite's arbitrary one.
 */
@Database(
    entities = [Task::class, ArticleEntity::class, OutboxEntity::class, ArticleRemoteKeys::class],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NeverLateDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    abstract fun articleDao(): ArticleDao

    abstract fun articleRemoteKeysDao(): ArticleRemoteKeysDao

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
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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

        /**
         * `4 -> 5` (feature 13b): adds [Task.priority]. Unlike [MIGRATION_3_4]'s *nullable* column,
         * this one is **`NOT NULL`**, so SQLite needs a `DEFAULT` to give every pre-existing row a
         * value — here `'NONE'`, the stored form ([Priority.name]) of [Priority.NONE], which is
         * exactly what a task with no priority set should read as after the upgrade. Without the
         * default, `ALTER TABLE ... ADD COLUMN ... NOT NULL` would fail on a non-empty table.
         *
         * We hand-write this `Migration` rather than use an `@AutoMigration`. For a purely additive
         * change like this, Room could *generate* the SQL by diffing the exported `4.json`/`5.json`
         * schemas (`@AutoMigration(from = 4, to = 5)` on the `@Database`), and that is the right
         * tool once schemas are exported. We write it by hand here because the lesson's whole point
         * is to *see* the SQL a migration runs; the tutorial contrasts the two approaches.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN priority TEXT NOT NULL DEFAULT 'NONE'")
            }
        }

        /**
         * `5 -> 6` (feature 13c): adds the [ArticleRemoteKeys] table and [ArticleEntity.remoteOrder],
         * both purely additive like [MIGRATION_4_5]. Even though `articles` is *only* a cache (unlike
         * `tasks`, it fully repopulates from the network on the next refresh, so a destructive
         * migration would be technically tolerable for that table alone), this database also holds
         * `tasks`/`task_outbox`, and since feature 13 (guest mode) a guest's tasks live **only**
         * on-device. Falling back to `fallbackToDestructiveMigration` here would wipe those tasks
         * purely as a side effect of an unrelated Articles change — so this migration stays additive,
         * the same reasoning [MIGRATION_3_4]/[MIGRATION_4_5] already documented.
         *
         * `remoteOrder`'s `NOT NULL DEFAULT 0` (same shape as [MIGRATION_4_5]'s `priority` column)
         * gives every article already cached before this upgrade a valid, if arbitrary, order — it
         * will simply be re-sorted (and the cache re-filled from page 0) the next time
         * [com.neverlate.data.articles.ArticlesRemoteMediator] runs a REFRESH, exactly as the class
         * KDoc there describes.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS article_remote_keys (" +
                        "articleId TEXT NOT NULL PRIMARY KEY, prevKey INTEGER, nextKey INTEGER)",
                )
                db.execSQL("ALTER TABLE articles ADD COLUMN remoteOrder INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
