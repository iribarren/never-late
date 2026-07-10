package com.neverlate.data.tasks

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The headline test for feature 13b: proves [NeverLateDatabase.MIGRATION_4_5] is **data-preserving**
 * — a real user upgrading from schema v4 to v5 keeps every task, and each one gains the new
 * [Task.priority] column defaulted to `NONE`.
 *
 * [MigrationTestHelper] is what makes this possible. It reads the schema JSON files Room exports
 * (`app/schemas/…/4.json`, `5.json`, shipped as androidTest assets — see `app/build.gradle.kts`)
 * to build a database at the *old* version, lets us seed real rows through raw SQL, then runs the
 * migration and **validates** the resulting schema against `5.json`. If [MIGRATION_4_5]'s SQL
 * disagreed with the entity (a typo'd column, wrong type, a missing `NOT NULL`), `runMigrationsAndValidate`
 * would fail — which is exactly the safety net `fallbackToDestructiveMigration` never gave us.
 *
 * Needs a connected device/emulator (`./gradlew :app:connectedDebugAndroidTest`); it is not part of
 * the JVM `:app:testDebugUnitTest` run.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NeverLateDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate4To5_preservesExistingTasks_andDefaultsPriorityToNone() {
        // 1. Create the database at version 4 (the pre-priority schema) and seed a real task row
        //    through raw SQL — MigrationTestHelper gives us the on-disk v4 schema, not the current
        //    entity, so we must spell out only the columns that existed at v4.
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO tasks (title, estimatedDurationMillis, deadline, updatedAt, syncState, deleted, completedAt)
                VALUES ('Comprar leche', 600000, 1751900000000, 42, 'SYNCED', 0, NULL)
                """.trimIndent(),
            )
        }

        // 2. Run the 4 -> 5 migration and validate the result matches the exported 5.json schema.
        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, NeverLateDatabase.MIGRATION_4_5)

        // 3. The row survived, its original fields are intact, and the new column defaulted to NONE.
        db.query("SELECT title, estimatedDurationMillis, deadline, updatedAt, syncState, priority FROM tasks").use { cursor ->
            assertEquals("exactly one row should survive the migration", 1, cursor.count)
            cursor.moveToFirst()
            assertEquals("Comprar leche", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals(600000L, cursor.getLong(cursor.getColumnIndexOrThrow("estimatedDurationMillis")))
            assertEquals(1751900000000L, cursor.getLong(cursor.getColumnIndexOrThrow("deadline")))
            assertEquals(42L, cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt")))
            assertEquals("SYNCED", cursor.getString(cursor.getColumnIndexOrThrow("syncState")))
            // The key assertion: the NOT NULL DEFAULT 'NONE' gave the pre-existing row a valid value.
            assertEquals("NONE", cursor.getString(cursor.getColumnIndexOrThrow("priority")))
        }
        db.close()
    }

    @Test
    fun migrate4To5_isNotDestructive_keepsMultipleRows() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL("INSERT INTO tasks (title, updatedAt, syncState, deleted) VALUES ('A', 1, 'SYNCED', 0)")
            db.execSQL("INSERT INTO tasks (title, updatedAt, syncState, deleted) VALUES ('B', 2, 'SYNCED', 0)")
            db.execSQL("INSERT INTO tasks (title, updatedAt, syncState, deleted) VALUES ('C', 3, 'PENDING_CREATE', 0)")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, NeverLateDatabase.MIGRATION_4_5)

        db.query("SELECT COUNT(*) FROM tasks WHERE priority = 'NONE'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("all three rows survive and default to NONE", 3, cursor.getInt(0))
        }
        // Sanity: the migration added the column rather than replacing the table (which would have
        // wiped the rows) — i.e. no row was lost.
        db.query("SELECT COUNT(*) FROM tasks").use { cursor ->
            cursor.moveToFirst()
            assertFalse("no rows were dropped", cursor.getInt(0) == 0)
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test-db"
    }
}
