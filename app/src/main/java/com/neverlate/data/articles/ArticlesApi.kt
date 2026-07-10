package com.neverlate.data.articles

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Typed description of the remote articles endpoint — this project's first use of
 * **Retrofit**. Instead of writing HTTP requests by hand (building a URL, opening a connection,
 * reading the response body, parsing JSON…), Retrofit turns an interface like this one into a
 * working implementation at runtime: [GET] tells it which HTTP verb and path to call, and the
 * return type tells it what to deserialize the response body into.
 *
 * Feature 13c replaces the original "fetch everything in one call" shape
 * (`GET("articles.json"): List<ArticleDto>`) with a **paginated** one: [getArticles] now takes
 * [page]/[size] `@Query` parameters and returns one [ArticlesPageDto] "page" at a time, matching
 * the backend's `GET /articles?page=&size=` contract (`docs/api/contract.md` §7). [getArticles]
 * is a `suspend` function — same coroutine building block already used throughout this project
 * (e.g. [com.neverlate.data.tasks.TaskDao]'s writes) — which is what lets Retrofit run the actual
 * network call on a background thread and simply resume this coroutine with the result, instead
 * of the caller needing to manage a callback or a background thread itself. [ArticlesRemoteMediator]
 * is the **only** caller: this endpoint is never fetched directly by a ViewModel or screen.
 *
 * See [ArticlesNetwork] for how an implementation of this interface is actually constructed
 * (base URL, HTTP client, JSON converter).
 */
interface ArticlesApi {
    /**
     * Fetches one page of the article catalog, still in wire format ([ArticleDto] inside
     * [ArticlesPageDto]) — mapping to the domain [Article] type happens later, in
     * [ArticlesRemoteMediator].
     *
     * [page] is the zero-based page index; [size] is the requested page size (the server may
     * clamp it — see [ArticlesPageDto.size], the size it actually used).
     */
    @GET("articles")
    suspend fun getArticles(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ArticlesPageDto
}
