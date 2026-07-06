package com.neverlate.data.articles

import kotlinx.serialization.Serializable

/**
 * A single time-management article, as the whole app understands it: independent of where the
 * data actually comes from. Since feature 10, that source is a remote JSON API cached locally in
 * Room (see [com.neverlate.data.articles.CachingArticleRepository]) — the wire format the API
 * sends ([ArticleDto]) differs from this class on purpose (`article_id`/`content` instead of
 * `id`/`body`, no `summary`), so [ArticleDto.toDomain] does the mapping. [ArticleRepository]
 * callers (ViewModels, UI) only ever see this stable [Article] type, never the DTO.
 *
 * `@Serializable` (from kotlinx.serialization) is unused by the current implementation — Room
 * stores [Article] via the separate [ArticleEntity] instead — but is kept here as a lightweight,
 * future-proof default in case a caller ever needs to serialize an [Article] directly (e.g. to
 * pass one through a navigation argument).
 */
@Serializable
data class Article(
    val id: String,
    val title: String,
    val summary: String,
    val body: String,
)
