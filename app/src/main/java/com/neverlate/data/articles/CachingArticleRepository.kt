package com.neverlate.data.articles

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * [ArticleRepository] backed by a remote REST API ([api]) with a local Room cache ([dao]) as the
 * **single source of truth**.
 *
 * "Single source of truth" means the UI never reads [api] directly: [getArticles] and
 * [getArticleById] always read [dao], full stop. [refresh] is the only function that talks to the
 * network, and its entire job is to update [dao] so that the *next* read reflects it — it never
 * hands network data straight to a caller. This is what makes the app usable offline (US-2 in the
 * feature spec): once anything has been cached, reads keep working with zero network calls.
 *
 * This is also what a *stale-while-revalidate* strategy looks like in code: instead of blocking
 * the UI on the network before showing anything, [com.neverlate.ui.articles.ArticlesViewModel]
 * reads the (possibly stale) cache first, shows that immediately, and calls [refresh] afterwards
 * to bring the cache up to date — updating what the UI shows a second time only if [refresh]
 * actually changed something.
 */
class CachingArticleRepository(
    private val api: ArticlesApi,
    private val dao: ArticleDao,
) : ArticleRepository {

    override suspend fun getArticles(): List<Article> = dao.getAll().map { it.toDomain() }

    override suspend fun getArticleById(id: String): Article? = dao.getById(id)?.toDomain()

    override suspend fun refresh(): RefreshResult = withContext(Dispatchers.IO) {
        // The network call, JSON parsing (inside `api.getArticles()`) and the Room writes below
        // are all blocking work, so this whole function runs on Dispatchers.IO — the same reason
        // LocalArticleRepository used to, for reading assets.
        try {
            val articles = api.getArticles().map { it.toDomain() }

            // Clear-then-insert (rather than only upserting) is what makes the cache a faithful
            // mirror of the server's current catalog: upsertAll alone would update/add articles
            // the server still has, but would never remove one the server deleted. There is a
            // brief window here where the table is empty if the process died between the two
            // calls; acceptable for a pre-release app with no real migration story yet (see
            // NeverLateDatabase's KDoc), and no worse than the destructive-migration trade-off
            // already accepted there.
            dao.clear()
            dao.upsertAll(articles.map { it.toEntity() })

            RefreshResult.Success
        } catch (error: IOException) {
            // No connectivity, DNS failure, timeout, etc. The existing cache is simply left as-is
            // because nothing was written above.
            RefreshResult.Error(error)
        } catch (error: HttpException) {
            // The server responded, but with a non-2xx status (404, 500...).
            RefreshResult.Error(error)
        } catch (error: SerializationException) {
            // The server responded with 2xx, but the body wasn't the JSON shape ArticleDto expects.
            RefreshResult.Error(error)
        }
        // Deliberately NOT `catch (error: Throwable)`: that would also swallow
        // kotlinx.coroutines.CancellationException, which every coroutine relies on propagating
        // to actually stop when its scope is cancelled (e.g. the ViewModel is cleared mid-refresh).
    }
}
