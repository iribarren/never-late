package com.neverlate.data.articles

import android.content.Context
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val ARTICLES_ASSET_FILE = "articles.json"
private const val LOG_TAG = "LocalArticleRepository"

/**
 * [ArticleRepository] backed by a JSON file bundled in `assets/`
 * (`app/src/main/assets/articles.json`). This is the only implementation for now, and it is
 * enough to satisfy this feature's "works fully offline" requirement.
 */
class LocalArticleRepository(private val context: Context) : ArticleRepository {

    // `ignoreUnknownKeys` keeps a JSON file with extra fields from crashing the parse — useful
    // once feature 10's API DTOs start diverging slightly from this local file.
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getArticles(): List<Article> = withContext(Dispatchers.IO) {
        // Reading assets and parsing JSON are both blocking calls, so this whole function runs
        // on Dispatchers.IO (a thread pool meant for blocking work) instead of the caller's
        // dispatcher, which is typically Dispatchers.Main in a ViewModel.
        try {
            val text = context.assets.open(ARTICLES_ASSET_FILE).bufferedReader().use { it.readText() }
            json.decodeFromString<List<Article>>(text)
        } catch (error: IOException) {
            // Missing/unreadable asset: log and degrade to an empty list rather than crashing.
            Log.w(LOG_TAG, "Could not read $ARTICLES_ASSET_FILE", error)
            emptyList()
        } catch (error: SerializationException) {
            // Malformed JSON: same graceful degradation.
            Log.w(LOG_TAG, "Could not parse $ARTICLES_ASSET_FILE", error)
            emptyList()
        }
    }

    override suspend fun getArticleById(id: String): Article? =
        getArticles().firstOrNull { it.id == id }
}
