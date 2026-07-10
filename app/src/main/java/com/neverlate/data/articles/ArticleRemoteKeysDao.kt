package com.neverlate.data.articles

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room-generated access to the `article_remote_keys` table (see [ArticleRemoteKeys]'s KDoc for
 * what it stores and why it is a separate table). Used exclusively by [ArticlesRemoteMediator] —
 * no ViewModel or screen ever reads this DAO directly, since it carries no article content.
 */
@Dao
interface ArticleRemoteKeysDao {
    /** The remote-key bookkeeping row for the article with this id, or null if it isn't cached (yet). */
    @Query("SELECT * FROM article_remote_keys WHERE articleId = :articleId")
    suspend fun remoteKeysByArticleId(articleId: String): ArticleRemoteKeys?

    /** Inserts every key in [keys], replacing any existing row for the same article id. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(keys: List<ArticleRemoteKeys>)

    /** Empties the table. Used by [ArticlesRemoteMediator] on `REFRESH`, alongside [ArticleDao.clear]. */
    @Query("DELETE FROM article_remote_keys")
    suspend fun clear()
}
