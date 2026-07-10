package com.neverlate.data.articles

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.neverlate.data.tasks.NeverLateDatabase
import java.io.IOException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** The zero-based index of the catalog's first page — both the API's convention and REFRESH's target. */
private const val ARTICLES_STARTING_PAGE = 0

/**
 * The network half of Paging 3's split responsibility for this feature: [ArticleDao.pagingSource]
 * (the *local* half) only ever reads from Room, and knows nothing about HTTP; this
 * [RemoteMediator] is the only thing that talks to [api], and its whole job is to keep Room
 * up to date so the *next* local read reflects it — the exact same "network writes, cache reads"
 * split [CachingArticleRepository] already used pre-13c, now expressed through Paging's own
 * extension point instead of a hand-written `refresh()`.
 *
 * Paging calls [load] with one of three [LoadType]s, matching how a user can scroll a list:
 * - **REFRESH**: first load, or [androidx.paging.compose.LazyPagingItems.refresh] (pull-to-refresh
 *   in [com.neverlate.ui.articles.ArticlesScreen]). Always (re-)fetches page 0.
 * - **APPEND**: the user scrolled near the bottom (governed by `PagingConfig.prefetchDistance`).
 *   Fetches the page after the last cached article's remembered [ArticleRemoteKeys.nextKey].
 * - **PREPEND**: scrolling *up* past the first loaded item. This catalog has no "page before 0"
 *   concept (feature 13c is deliberately one-directional — see the feature spec's *Out of Scope*),
 *   so this always reports `endOfPaginationReached = true` immediately, with no network call.
 *
 * `@OptIn(ExperimentalPagingApi::class)`: `RemoteMediator` is Paging's network+database
 * integration point and is still marked experimental upstream, even though it is the documented,
 * standard way to back a `Pager` with both a network source and a local cache.
 */
@OptIn(ExperimentalPagingApi::class)
class ArticlesRemoteMediator(
    private val api: ArticlesApi,
    private val database: NeverLateDatabase,
) : RemoteMediator<Int, ArticleEntity>() {

    private val articleDao = database.articleDao()
    private val remoteKeysDao = database.articleRemoteKeysDao()

    /**
     * `LAUNCH_INITIAL_REFRESH` makes the very first [Pager][androidx.paging.Pager] collection
     * always run a REFRESH before showing anything from the cache — so opening Articles online
     * re-syncs page 0 immediately, rather than trusting whatever Room already had on disk from a
     * previous session until the user explicitly pulls to refresh.
     */
    override suspend fun initialize(): InitializeAction = InitializeAction.LAUNCH_INITIAL_REFRESH

    override suspend fun load(loadType: LoadType, state: PagingState<Int, ArticleEntity>): MediatorResult {
        val page = when (loadType) {
            LoadType.REFRESH -> ARTICLES_STARTING_PAGE

            LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)

            LoadType.APPEND -> {
                // The last article Paging currently has loaded tells us which page to fetch next,
                // via the key we stored for it the last time we wrote a page.
                val lastItem = state.lastItemOrNull()
                    ?: return MediatorResult.Success(endOfPaginationReached = true)
                val nextKey = remoteKeysDao.remoteKeysByArticleId(lastItem.id)?.nextKey
                    // No next key recorded means that item's page was already the last one.
                    ?: return MediatorResult.Success(endOfPaginationReached = true)
                nextKey
            }
        }

        return try {
            val pageSize = state.config.pageSize
            val response = api.getArticles(page = page, size = pageSize)
            // The contract's recommended check (docs/api/contract.md §7): a short page means there
            // is nothing left to fetch. `total` (also on the response) is available as a
            // cross-check but isn't needed for this to be correct on its own.
            val endOfPaginationReached = response.items.size < response.size

            // REFRESH clears + repopulates the cache, and APPEND adds to it, all inside one Room
            // transaction: if the process died mid-write, either the whole page (articles +
            // remote keys) landed, or none of it did — never a half-written page that could
            // desync remoteOrder from the remote keys pointing at it.
            database.withTransaction {
                if (loadType == LoadType.REFRESH) {
                    articleDao.clear()
                    remoteKeysDao.clear()
                }

                val prevKey = if (page == ARTICLES_STARTING_PAGE) null else page - 1
                val nextKey = if (endOfPaginationReached) null else page + 1

                remoteKeysDao.insertAll(
                    response.items.map { dto -> ArticleRemoteKeys(articleId = dto.articleId, prevKey = prevKey, nextKey = nextKey) },
                )
                articleDao.upsertAll(
                    response.items.mapIndexed { indexInPage, dto ->
                        // remoteOrder is the article's position in the *whole* catalog, not just
                        // within this page — see ArticleEntity's KDoc for why that distinction is
                        // what keeps ORDER BY remoteOrder stable across pages.
                        dto.toDomain().toEntity(remoteOrder = page * pageSize + indexInPage)
                    },
                )
            }

            MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
        } catch (error: IOException) {
            // No connectivity, DNS failure, timeout, etc.
            MediatorResult.Error(error)
        } catch (error: HttpException) {
            // The server responded, but with a non-2xx status (404, 500...).
            MediatorResult.Error(error)
        } catch (error: SerializationException) {
            // The server responded with 2xx, but the body wasn't the JSON shape ArticlesPageDto expects.
            MediatorResult.Error(error)
        }
        // Deliberately NOT `catch (error: Throwable)`: that would also swallow
        // kotlinx.coroutines.CancellationException, which every coroutine relies on propagating to
        // actually stop when its scope is cancelled — same reasoning the pre-13c
        // CachingArticleRepository.refresh already documented for this exact catch list.
    }
}
