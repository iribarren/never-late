package com.neverlate.data.articles

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.neverlate.data.tasks.NeverLateDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * How many articles [ArticlesRemoteMediator] fetches per network page, and how many rows
 * [Pager] loads from Room per local page. Kept as one constant (rather than duplicated) so the
 * two always agree — a Paging `Pager` reading smaller chunks from Room than the mediator writes
 * per network page would still work, but pointlessly: it would only ever have "whole" pages to
 * hand out anyway.
 */
internal const val ARTICLES_PAGE_SIZE = 20

/**
 * [ArticleRepository] backed by a remote REST API ([api]) with a local Room cache
 * ([database]'s [ArticleDao]) as the **single source of truth**.
 *
 * "Single source of truth" means the UI never reads [api] directly: [getArticleById] and
 * [articlesPager] always read Room, full stop. Through feature 13b that split was hand-written —
 * a `getArticles()` method read the cache, a separate `refresh()` cleared and re-populated it from
 * the network, and [com.neverlate.ui.articles.ArticlesViewModel] called both in sequence
 * (*stale-while-revalidate*). Feature 13c replaces that pair with Jetpack Paging 3: [articlesPager]
 * builds a single [Pager] that reads from [ArticleDao.pagingSource] (the local half) and writes
 * through [ArticlesRemoteMediator] (the network half, see its KDoc) — the same "network writes,
 * cache reads" shape, now expressed as one continuous [Flow] of [PagingData] instead of two
 * separately-called methods. [database] (rather than a bare [ArticleDao]) is what this class needs
 * now: [ArticlesRemoteMediator] writes both the `articles` and `article_remote_keys` tables inside
 * one Room transaction (`database.withTransaction`), which requires the database instance, not
 * just one of its DAOs.
 */
class CachingArticleRepository(
    private val api: ArticlesApi,
    private val database: NeverLateDatabase,
) : ArticleRepository {

    private val dao = database.articleDao()

    @OptIn(ExperimentalPagingApi::class)
    override fun articlesPager(): Flow<PagingData<Article>> = Pager(
        config = PagingConfig(pageSize = ARTICLES_PAGE_SIZE, enablePlaceholders = false),
        remoteMediator = ArticlesRemoteMediator(api, database),
        pagingSourceFactory = { dao.pagingSource() },
    ).flow.map { pagingData -> pagingData.map { entity -> entity.toDomain() } }

    override suspend fun getArticleById(id: String): Article? = dao.getById(id)?.toDomain()
}
