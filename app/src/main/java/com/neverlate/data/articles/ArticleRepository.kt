package com.neverlate.data.articles

/**
 * Reads time-management articles.
 *
 * Declared as an interface — same reasoning as
 * [com.neverlate.data.UserPreferencesRepository] — so the UI and ViewModels
 * ([com.neverlate.ui.articles.ArticlesViewModel], [com.neverlate.ui.articles.ArticleDetailViewModel])
 * depend only on this contract, never on [LocalArticleRepository] directly. Feature 10 will add a
 * remote implementation (Retrofit) behind this same interface, without touching presentation code.
 */
interface ArticleRepository {
    /** All available articles, in a stable display order. */
    suspend fun getArticles(): List<Article>

    /** The article whose [Article.id] matches [id], or null if there is no such article. */
    suspend fun getArticleById(id: String): Article?
}
