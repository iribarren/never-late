package com.neverlate.data.articles

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

/**
 * Reads time-management articles.
 *
 * Declared as an interface — same reasoning as
 * [com.neverlate.data.UserPreferencesRepository] — so the UI and ViewModels
 * ([com.neverlate.ui.articles.ArticlesViewModel], [com.neverlate.ui.articles.ArticleDetailViewModel])
 * depend only on this contract, never on a concrete implementation directly.
 *
 * Since feature 10, the only implementation is [CachingArticleRepository]: a local Room cache is
 * the **single source of truth** both methods here read from. Feature 13c changes *how* that cache
 * gets filled — [articlesPager] streams it page-by-page via Jetpack Paging 3 instead of the old
 * `getArticles()`/`refresh()` pair (see [CachingArticleRepository]'s KDoc for the full before/after) —
 * but the "UI never talks to the network directly" shape is unchanged.
 */
interface ArticleRepository {
    /**
     * A stream of [Article] pages, loaded incrementally as the UI scrolls and cached in Room along
     * the way (see [CachingArticleRepository.articlesPager]). Each new [PagingData] emission
     * reflects the current state of that cache; the UI collects this with
     * `collectAsLazyPagingItems()` (see [com.neverlate.ui.articles.ArticlesViewModel]) rather than
     * calling a one-shot "get everything" method the way [getArticleById] still does below.
     */
    fun articlesPager(): Flow<PagingData<Article>>

    /** The cached article whose [Article.id] matches [id], or null if there is no such article. */
    suspend fun getArticleById(id: String): Article?
}
