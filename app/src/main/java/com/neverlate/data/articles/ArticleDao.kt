package com.neverlate.data.articles

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room-generated access to the `articles` table.
 *
 * Contrast with [com.neverlate.data.tasks.TaskDao]: that DAO returns [kotlinx.coroutines.flow.Flow]
 * from its reads, because the tasks list/widget/notification all need to react live to every
 * change. Articles still don't need that (nothing else observes the catalog live), but since
 * feature 13c the list is no longer read as one plain `List` either: [pagingSource] returns a
 * Room/KSP-generated [PagingSource], the `Pager`'s local read side (see
 * [CachingArticleRepository.articlesPager]) — [ArticlesRemoteMediator] is the only thing that
 * writes to this table, [getById] and [pagingSource] are the only ways anything reads from it.
 */
@Dao
interface ArticleDao {
    /**
     * The Paging 3 read side of the `articles` table, ordered by [ArticleEntity.remoteOrder] so
     * pages come back in the server's stable order rather than SQLite's arbitrary row order.
     * Unlike every other method here, this one is **not** `suspend`: Room generates a
     * [PagingSource] implementation that Paging's own `Pager` drives (loading one chunk of rows at
     * a time as the UI scrolls), so the loading itself is Paging's job, not this DAO's.
     */
    @Query("SELECT * FROM articles ORDER BY remoteOrder ASC")
    fun pagingSource(): PagingSource<Int, ArticleEntity>

    /** The cached article whose id matches [id], or null if nothing is cached under that id. */
    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun getById(id: String): ArticleEntity?

    /**
     * Inserts every item in [items], replacing any existing row that shares its primary key.
     * [OnConflictStrategy.REPLACE] is what makes this an "upsert" (update-or-insert) rather than a
     * plain insert that would fail on a duplicate id — exactly what [ArticlesRemoteMediator] needs
     * when a page it already cached is re-fetched (e.g. on `REFRESH`).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ArticleEntity>)

    /** Empties the table. Used by [ArticlesRemoteMediator] on `REFRESH`, before it re-inserts page 1. */
    @Query("DELETE FROM articles")
    suspend fun clear()
}
