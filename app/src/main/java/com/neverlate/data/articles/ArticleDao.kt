package com.neverlate.data.articles

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room-generated access to the `articles` table.
 *
 * Contrast with [com.neverlate.data.tasks.TaskDao]: that DAO returns [kotlinx.coroutines.flow.Flow]
 * from its reads, because the tasks list/widget/notification all need to react live to every
 * change. Articles don't work that way — [com.neverlate.ui.articles.ArticlesViewModel] loads the
 * cache once (and again on an explicit [com.neverlate.data.articles.ArticleRepository.refresh]),
 * matching the one-shot loading feature 03 already established — so every method here is a plain
 * one-shot `suspend` function instead.
 */
@Dao
interface ArticleDao {
    /** All cached articles, in whatever order SQLite happens to return them. */
    @Query("SELECT * FROM articles")
    suspend fun getAll(): List<ArticleEntity>

    /** The cached article whose id matches [id], or null if nothing is cached under that id. */
    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun getById(id: String): ArticleEntity?

    /**
     * Inserts every item in [items], replacing any existing row that shares its primary key.
     * [OnConflictStrategy.REPLACE] is what makes this an "upsert" (update-or-insert) rather than a
     * plain insert that would fail on a duplicate id — exactly what a refresh needs: articles the
     * server still has get their content updated in place, by id.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ArticleEntity>)

    /** Empties the table. Used before [upsertAll] so a refresh also drops articles the server removed. */
    @Query("DELETE FROM articles")
    suspend fun clear()
}
