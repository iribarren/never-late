package com.neverlate.backend.articles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The wire shape (contract.md §7) — unchanged from feature 10: deliberately distinct from the
 *  client's `Article` domain model (no `summary`; the client derives one from `content` via
 *  `ArticleDto.toDomain()`). The JSON key stays snake_case (`article_id`) while the Kotlin
 *  property is camelCase — the same wire-≠-domain teaching point [com.neverlate.backend.tasks.TaskDto]
 *  already makes for tasks. */
@Serializable
data class ArticleDto(
    @SerialName("article_id") val articleId: String,
    val title: String,
    val content: String,
)

/** `GET /articles` response (contract.md §7): one page of the catalog plus enough metadata
 *  (`page`, `size`, `total`) for the client to derive `endOfPaginationReached` and, if it wants,
 *  the total page count. */
@Serializable
data class ArticlesPage(
    val items: List<ArticleDto>,
    val page: Int,
    val size: Int,
    val total: Int,
)
