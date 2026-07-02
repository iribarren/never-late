package com.neverlate.data.articles

import kotlinx.serialization.Serializable

/**
 * A single time-management article, as the whole app understands it: independent of whether the
 * data came from bundled assets (this feature) or, later, a remote API (feature 10).
 *
 * `@Serializable` (from kotlinx.serialization) lets [LocalArticleRepository] parse this class
 * directly out of `articles.json` without a separate DTO — fine while the JSON shape and this
 * domain model match. If a future remote implementation needs a different wire shape, that
 * mapping happens inside the new repository implementation, not here: [ArticleRepository]
 * callers (ViewModels, UI) only ever see this stable [Article] type.
 */
@Serializable
data class Article(
    val id: String,
    val title: String,
    val summary: String,
    val body: String,
)
