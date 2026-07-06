package com.neverlate.data.articles

import retrofit2.http.GET

/**
 * Typed description of the remote articles endpoint — this project's first use of
 * **Retrofit**. Instead of writing HTTP requests by hand (building a URL, opening a connection,
 * reading the response body, parsing JSON…), Retrofit turns an interface like this one into a
 * working implementation at runtime: [GET] tells it which HTTP verb and path to call, and the
 * return type tells it what to deserialize the response body into.
 *
 * [getArticles] is a `suspend` function — same coroutine building block already used throughout
 * this project (e.g. [com.neverlate.data.tasks.TaskDao]'s writes) — which is what lets Retrofit
 * run the actual network call on a background thread and simply resume this coroutine with the
 * result, instead of the caller needing to manage a callback or a background thread itself.
 *
 * See [ArticlesNetwork] for how an implementation of this interface is actually constructed
 * (base URL, HTTP client, JSON converter).
 */
interface ArticlesApi {
    /**
     * Fetches the full article catalog as it currently exists on the server, still in its wire
     * format ([ArticleDto]) — mapping to the domain [Article] type happens later, in
     * [CachingArticleRepository.refresh].
     */
    @GET("articles.json")
    suspend fun getArticles(): List<ArticleDto>
}
