package com.neverlate.data.articles

/**
 * Reads time-management articles, and knows how to refresh them from their remote source.
 *
 * Declared as an interface — same reasoning as
 * [com.neverlate.data.UserPreferencesRepository] — so the UI and ViewModels
 * ([com.neverlate.ui.articles.ArticlesViewModel], [com.neverlate.ui.articles.ArticleDetailViewModel])
 * depend only on this contract, never on a concrete implementation directly.
 *
 * Since feature 10, the only implementation is [CachingArticleRepository]: a local Room cache is
 * the **single source of truth** that [getArticles]/[getArticleById] read from, and a remote REST
 * API ([ArticlesApi]) is the thing [refresh] pulls fresh data from to keep that cache up to date.
 * This split is what lets the UI show a *stale-while-revalidate* experience — cached content
 * immediately, refreshed in the background — and keep working fully offline once something has
 * been cached at least once.
 *
 * [refresh] was added **additively** on top of the two read methods that feature 03 already
 * shipped: [getArticles] and [getArticleById] never throw or report failure (a network error
 * simply leaves the existing cache untouched), so on their own they cannot tell a ViewModel
 * "empty because there is truly nothing" apart from "empty because the network failed and there
 * is no cache yet". [refresh] closes that gap by reporting success/failure explicitly, without
 * changing the meaning or signature of the two original methods.
 */
interface ArticleRepository {
    /** All available articles, in a stable display order, as currently cached. */
    suspend fun getArticles(): List<Article>

    /** The cached article whose [Article.id] matches [id], or null if there is no such article. */
    suspend fun getArticleById(id: String): Article?

    /**
     * Attempts to download the latest articles from the remote source and replace the local
     * cache with them. Callers keep using [getArticles]/[getArticleById] to read the result — this
     * function only reports whether the attempt succeeded, so the UI can distinguish a genuinely
     * empty catalog from a failed refresh (see [RefreshResult]).
     */
    suspend fun refresh(): RefreshResult
}

/**
 * The outcome of an [ArticleRepository.refresh] attempt.
 *
 * A `sealed interface` (rather than a plain `Boolean` or nullable `Throwable`) makes the two
 * possible outcomes explicit at the call site and lets [Error] carry the [Throwable] that caused
 * it, e.g. for logging — the same modeling style already used for
 * [com.neverlate.ui.articles.ArticlesUiState].
 */
sealed interface RefreshResult {
    /** The remote fetch succeeded and the cache now reflects it. */
    data object Success : RefreshResult

    /** The remote fetch failed; the existing cache (if any) was left untouched. */
    data class Error(val cause: Throwable) : RefreshResult
}
