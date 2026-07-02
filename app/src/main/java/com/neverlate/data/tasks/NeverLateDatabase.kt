package com.neverlate.data.tasks

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's single Room database. `@Database` is the annotation that ties an entity list
 * ([Task]) to a set of DAOs ([TaskDao]) and generates the actual SQLite-backed implementation of
 * this abstract class via KSP.
 *
 * Only [Task] lives here for now, but the widget (feature 05), lock-screen notification
 * (feature 06), reminders (feature 09) and remote sync (feature 11) are all expected to extend
 * this schema — which is why it is already versioned (`version = 1`) instead of left implicit.
 *
 * `exportSchema = false` opts out of Room writing a JSON schema-history file on every build (its
 * usual purpose is powering *real* Migration tests). Since this database currently only ever
 * falls back to destroying and recreating itself (see [fallbackToDestructiveMigration] below),
 * there is no migration history to check that file against yet; revisit this once a real
 * Migration is written.
 */
@Database(entities = [Task::class], version = 1, exportSchema = false)
abstract class NeverLateDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

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
                    // from here on (features 05/06/09/11 are all expected to touch this schema)
                    // must ship a real Migration instead.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
