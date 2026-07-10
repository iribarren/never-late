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
 * Deliberately shaped exactly like [Article] (plus one bookkeeping column) rather than like
 * [ArticleDto]: the cache stores the app's stable domain shape, not the API's wire format, so
 * [toDomain] and [Article.toEntity] are trivial one-to-one mappings. All the interesting mapping
 * work (deriving [Article.summary], renaming fields) already happened once, in
 * [ArticleDto.toDomain], when the row was written.
 *
 * [remoteOrder] is new in feature 13c: SQLite has **no inherent row order**, so once articles
 * arrive in pages (rather than as one whole-catalog write), something has to remember the
 * server's order across pages, or [ArticleDao.pagingSource] could return rows out of sequence —
 * visible jumps or gaps as the user scrolls. [ArticlesRemoteMediator] sets it to each article's
 * **global** catalog index (`page * size + indexWithinPage`) as it writes a page, and
 * `ORDER BY remoteOrder ASC` is what makes the paging source's order match the server's. It
 * defaults to `0` so existing call sites (tests, previews) that only care about the article's
 * *content* can keep constructing an [ArticleEntity] without naming it.
 */
@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val summary: String,
    val body: String,
    val remoteOrder: Int = 0,
)

/** Maps a cached row back to the domain [Article] type that the rest of the app depends on. */
fun ArticleEntity.toDomain(): Article = Article(
    id = id,
    title = title,
    summary = summary,
    body = body,
)

/**
 * Maps a domain [Article] to the row shape [ArticleDao] persists it as. [remoteOrder] is not part
 * of the domain [Article] (it is a purely local, paging-bookkeeping concern), so it is supplied
 * separately by the only caller that actually knows a page's position — [ArticlesRemoteMediator] —
 * rather than living on [Article] itself.
 */
fun Article.toEntity(remoteOrder: Int = 0): ArticleEntity = ArticleEntity(
    id = id,
    title = title,
    summary = summary,
    body = body,
    remoteOrder = remoteOrder,
)
