package com.neverlate.backend.db

import com.neverlate.backend.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import javax.sql.DataSource

/**
 * Persistence, deliberately kept as **plain JDBC** rather than an ORM (Exposed was the other
 * option per the feature spec) — with only two tables and simple queries, raw SQL keeps the
 * concept count low: the lesson is "a real backend talks to a real relational DB", not "how to
 * use a Kotlin SQL DSL". [PostgresUserRepository] / [PostgresTaskRepository] hold the actual SQL.
 *
 * [HikariDataSource] is a *connection pool*: instead of opening a new TCP connection to Postgres
 * per request (slow, and Postgres caps concurrent connections), the pool keeps a small set of
 * connections open and hands them out/back. It's the de-facto standard pool on the JVM.
 */
fun createDataSource(config: Config): DataSource {
    val hikariConfig = HikariConfig().apply {
        jdbcUrl = config.databaseUrl
        username = config.databaseUser
        password = config.databasePassword
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 10
    }
    return HikariDataSource(hikariConfig)
}

/**
 * Creates the schema if it doesn't exist yet. A teaching-sized project with two tables doesn't
 * need a full migration framework (Flyway/Liquibase) — `CREATE TABLE IF NOT EXISTS` run once at
 * startup is simple and idempotent. A real production backend would reach for migrations once
 * the schema needs to evolve without downtime.
 */
fun initSchema(dataSource: DataSource) {
    dataSource.connection.use { conn: Connection ->
        conn.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS users (
                    id BIGSERIAL PRIMARY KEY,
                    email TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    created_at BIGINT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS tasks (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES users(id),
                    client_ref TEXT NOT NULL,
                    title TEXT NOT NULL,
                    estimated_duration_ms BIGINT,
                    deadline BIGINT,
                    updated_at BIGINT NOT NULL,
                    deleted BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at BIGINT NOT NULL,
                    UNIQUE (user_id, client_ref)
                )
                """.trimIndent(),
            )
            // Every task query is scoped to a user id (contract.md §1.2) — this index is what
            // keeps that scoping cheap as the table grows.
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_tasks_user_id ON tasks(user_id)",
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_tasks_user_updated_at ON tasks(user_id, updated_at)",
            )
            // Feature 12: refresh tokens are the first piece of auth state the backend keeps (the
            // access token stays a stateless JWT). Added additively — this backend owns real user
            // data, so unlike the client's Room `fallbackToDestructiveMigration`, schema changes
            // here are never destructive. `token_hash` is UNIQUE both for a fast indexed lookup on
            // every `/auth/refresh` call and to make a hash collision immediately visible as a
            // constraint violation rather than a silent mix-up.
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS refresh_tokens (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES users(id),
                    family_id TEXT NOT NULL,
                    token_hash TEXT NOT NULL UNIQUE,
                    created_at BIGINT NOT NULL,
                    expires_at BIGINT NOT NULL,
                    consumed_at BIGINT,
                    revoked BOOLEAN NOT NULL DEFAULT FALSE
                )
                """.trimIndent(),
            )
            // Reuse detection revokes a whole family in one statement (AuthService.refresh); this
            // index keeps that cheap.
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family_id ON refresh_tokens(family_id)",
            )
            // Feature 04c: real task completion. Added additively (nullable, no default row
            // rewrite needed) — same non-destructive policy as the rest of this schema; existing
            // rows simply get NULL (not completed).
            statement.execute(
                "ALTER TABLE tasks ADD COLUMN IF NOT EXISTS completed_at BIGINT",
            )
            // Feature 13b: task priority. NOT NULL with a DEFAULT so existing rows get 'NONE'
            // (the mirror of the client's MIGRATION_4_5); still additive and non-destructive.
            // Stored as TEXT — the enum's name — matching the wire values (contract.md §4).
            statement.execute(
                "ALTER TABLE tasks ADD COLUMN IF NOT EXISTS priority TEXT NOT NULL DEFAULT 'NONE'",
            )
        }
    }
}
