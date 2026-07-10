package com.neverlate.data.articles

import kotlinx.serialization.Serializable

/**
 * The wire shape of one "page" of the article catalog — what `GET /articles?page=&size=` actually
 * returns (`docs/api/contract.md` §7), wrapping a list of [ArticleDto] with the pagination
 * bookkeeping [ArticlesRemoteMediator] needs to know where it is in the catalog.
 *
 * A page/offset response (rather than an opaque `nextCursor`) is the right shape for a small,
 * static, append-only catalog like this one: [page]/[size] map directly onto the integer keys
 * [ArticlesRemoteMediator] stores per article in [ArticleRemoteKeys] (`prevKey`/`nextKey`), and
 * [total] lets the mediator (or a future UI) cross-check
 * [endOfPaginationReached][androidx.paging.RemoteMediator.MediatorResult.Success] against the
 * catalog's real size instead of trusting `items.size < size` alone.
 */
@Serializable
data class ArticlesPageDto(
    /** This page's articles, in the server's stable total order. Empty past the last page. */
    val items: List<ArticleDto>,
    /** The zero-based page index this response answers, echoed back from the request. */
    val page: Int,
    /**
     * The page size the server actually used (after clamping the requested [size] — see
     * [ArticlesApi.getArticles]). [ArticlesRemoteMediator] derives `endOfPaginationReached` as
     * `items.size < size`, so this must be the server's real page size, not the client's request.
     */
    val size: Int,
    /** The full catalog count — a cross-check for `endOfPaginationReached`, not load-bearing on its own. */
    val total: Int,
)
