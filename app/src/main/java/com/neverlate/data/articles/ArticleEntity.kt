package com.neverlate.data.articles

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The Room-persisted row for a single article — the local cache that makes
 * [CachingArticleRepository] work offline. Same `@Entity`/`@PrimaryKey` pattern as
 * [com.neverlate.data.tasks.Task] introduced first, except [id] is a `String` (the article's
 * slug-like id, e.g. `"pomodoro"`) rather than an auto-generated `Long`: articles come from a
 * remote catalog that already assigns each one a stable id, so there is nothing for SQLite to
 * generate.
 *
 * Deliberately shaped exactly like [Article] (same four fields) rather than like [ArticleDto]:
 * the cache stores the app's stable domain shape, not the API's wire format, so [toDomain] and
 * [Article.toEntity] are trivial one-to-one mappings. All the interesting mapping work (deriving
 * [summary], renaming fields) already happened once, in [ArticleDto.toDomain], when the row was
 * written.
 */
@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val summary: String,
    val body: String,
)

/** Maps a cached row back to the domain [Article] type that the rest of the app depends on. */
fun ArticleEntity.toDomain(): Article = Article(
    id = id,
    title = title,
    summary = summary,
    body = body,
)

/** Maps a domain [Article] to the row shape [ArticleDao] persists it as. */
fun Article.toEntity(): ArticleEntity = ArticleEntity(
    id = id,
    title = title,
    summary = summary,
    body = body,
)
