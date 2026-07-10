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
 * The headline test for feature 13b/13c: proves [NeverLateDatabase.MIGRATION_4_5] and
 * [NeverLateDatabase.MIGRATION_5_6] are each **data-preserving** — a real user upgrading through
 * these schema versions keeps every task/article they already had, gaining each migration's new
 * column(s) with a sane default instead of losing data to a destructive fallback.
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

    /**
     * Feature 13c's headline migration test: [NeverLateDatabase.MIGRATION_5_6] must preserve an
     * `articles` row already cached before the upgrade, giving it the new
     * [com.neverlate.data.articles.ArticleEntity.remoteOrder] column defaulted to `0` (it will be
     * re-sorted from page 0 the next time [com.neverlate.data.articles.ArticlesRemoteMediator]
     * runs a REFRESH — see that migration's KDoc).
     */
    @Test
    fun migrate5To6_preservesExistingArticles_andDefaultsRemoteOrderToZero() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO articles (id, title, summary, body)
                VALUES ('pomodoro', 'La técnica Pomodoro', 'Resumen breve.', 'Cuerpo completo sobre Pomodoro.')
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, NeverLateDatabase.MIGRATION_5_6)

        db.query("SELECT id, title, summary, body, remoteOrder FROM articles").use { cursor ->
            assertEquals("exactly one article should survive the migration", 1, cursor.count)
            cursor.moveToFirst()
            assertEquals("pomodoro", cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals("La técnica Pomodoro", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals("Resumen breve.", cursor.getString(cursor.getColumnIndexOrThrow("summary")))
            // The key assertion: the NOT NULL DEFAULT 0 gave the pre-existing row a valid value.
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("remoteOrder")))
        }
        db.close()
    }

    /**
     * The same migration must also create the new `article_remote_keys` table (empty, since it is
     * purely local Paging bookkeeping with no pre-13c equivalent to migrate data from) without
     * disturbing an unrelated, pre-existing `tasks` row sharing the same database.
     */
    @Test
    fun migrate5To6_createsArticleRemoteKeysTable_andKeepsExistingTasks() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO tasks (title, updatedAt, syncState, deleted, priority) " +
                    "VALUES ('Comprar leche', 1, 'SYNCED', 0, 'NONE')",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, NeverLateDatabase.MIGRATION_5_6)

        db.query("SELECT COUNT(*) FROM tasks").use { cursor ->
            cursor.moveToFirst()
            assertEquals("the pre-existing task must survive an unrelated Articles migration", 1, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM article_remote_keys").use { cursor ->
            cursor.moveToFirst()
            assertEquals("article_remote_keys starts empty; only ArticlesRemoteMediator populates it", 0, cursor.getInt(0))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test-db"
    }
}
