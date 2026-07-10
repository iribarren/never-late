package com.neverlate.backend.db

import com.neverlate.backend.Config
import com.neverlate.backend.articles.ArticleDto
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
            // Feature 13c: the article catalog becomes real backend data (previously a static
            // GitHub-raw JSON file fetched directly by the client). `position` is what gives
            // `GET /articles` its stable total order across pages (contract.md §7) — SQL tables
            // have no inherent row order, so without it the same offset could return different
            // rows between requests. `article_id` is UNIQUE so re-running the seed (see
            // [seedArticlesIfEmpty]) can never insert a duplicate.
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS articles (
                    id BIGSERIAL PRIMARY KEY,
                    article_id TEXT NOT NULL UNIQUE,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    position INT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_articles_position ON articles(position)",
            )
        }
    }
}

/**
 * Seeds the `articles` table from the bundled catalog **the first time** the backend starts
 * against an empty table; a no-op on every later startup, so it's safe to call unconditionally
 * from [com.neverlate.backend.main] right after [initSchema]. `position` is assigned by the seed
 * file's array order, which is what gives the endpoint its stable ordering guarantee.
 *
 * The seed content is bundled as a classpath resource (`resources/seed/articles.json`) rather
 * than read from a repo-relative path: the packaged Docker image (see backend/Dockerfile) only
 * ships the built jar, not the wider repo checkout, so a relative path to `docs/articles-api/`
 * would not resolve at runtime. That file stays the canonical, human-edited source; the bundled
 * copy is what actually ships (see its header comment).
 */
fun seedArticlesIfEmpty(dataSource: DataSource) {
    dataSource.connection.use { conn ->
        val alreadySeeded = conn.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM articles").use { rs ->
                rs.next()
                rs.getInt(1) > 0
            }
        }
        if (alreadySeeded) return

        val catalog = loadSeedCatalog()
        conn.prepareStatement(
            "INSERT INTO articles (article_id, title, content, position) VALUES (?, ?, ?, ?)",
        ).use { stmt ->
            catalog.forEachIndexed { position, article ->
                stmt.setString(1, article.articleId)
                stmt.setString(2, article.title)
                stmt.setString(3, article.content)
                stmt.setInt(4, position)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }
}

private fun loadSeedCatalog(): List<ArticleDto> {
    val json = ClassLoader.getSystemResourceAsStream("seed/articles.json")
        ?.bufferedReader()
        ?.readText()
        ?: error("Missing bundled seed/articles.json resource")
    return Json.decodeFromString(ListSerializer(ArticleDto.serializer()), json)
}
