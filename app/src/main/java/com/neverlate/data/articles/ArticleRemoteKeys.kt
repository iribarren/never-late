package com.neverlate.data.articles

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Paging 3's standard "remote keys" bookkeeping table: one row per cached article, remembering
 * which server page it came from (as an integer `prevKey`/`nextKey` pair) so
 * [ArticlesRemoteMediator] knows which page to fetch next without re-deriving it from
 * [ArticleEntity.remoteOrder] or re-asking the server.
 *
 * This is a **second**, separate Room table from `articles` (not extra columns bolted onto
 * [ArticleEntity]) because it models a different concern: [ArticleEntity] is the article's
 * content, [ArticleRemoteKeys] is purely local paging metadata that would mean nothing on the
 * wire and has no [Article] domain-model equivalent.
 */
@Entity(tableName = "article_remote_keys")
data class ArticleRemoteKeys(
    @PrimaryKey val articleId: String,
    /** The page index that comes *before* this article's page, or `null` if it was on page 0. */
    val prevKey: Int?,
    /** The page index to fetch next after this article's page, or `null` at the last page. */
    val nextKey: Int?,
)
