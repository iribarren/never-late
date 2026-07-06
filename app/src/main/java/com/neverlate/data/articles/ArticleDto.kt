package com.neverlate.data.articles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The shape the remote articles API actually sends over the wire — see
 * `docs/articles-api/articles.json` for the raw JSON this maps from, and
 * [com.neverlate.ui.articles.ArticlesRoute]'s KDoc chain back to [Article] for why the two shapes
 * are kept deliberately different.
 *
 * Two differences from [Article], both on purpose (this is this project's first real DTO ≠
 * domain-model split):
 * - **`article_id` instead of `id`**: the API uses `snake_case`, this codebase uses `camelCase`.
 *   [SerialName] (from kotlinx.serialization) tells the parser "the JSON key is `article_id`, but
 *   name the Kotlin property [articleId]" — the annotation bridges the two naming conventions
 *   without the Kotlin side having to adopt snake_case.
 * - **`content` instead of `body`, and no `summary` at all**: the API only ever sends a title and
 *   a full body; [toDomain] derives [Article.summary] from [content] via [summarize] so the rest
 *   of the app never has to know the API omits it.
 *
 * `@Serializable` is what lets [ArticlesApi] (through the kotlinx.serialization Retrofit
 * converter configured in [ArticlesNetwork]) turn a JSON array straight into `List<ArticleDto>`
 * with no hand-written parsing code.
 */
@Serializable
data class ArticleDto(
    @SerialName("article_id") val articleId: String,
    val title: String,
    val content: String,
)

/**
 * Maps a wire-format [ArticleDto] to the stable domain [Article] type that the rest of the app
 * depends on. Kept as a small top-level extension function (rather than a method on either
 * class) so it stays a **pure function**: given the same [ArticleDto], it always returns the same
 * [Article], with no Android dependency and no I/O — which is exactly what makes it trivial to
 * unit-test on the plain JVM, without a device or emulator.
 */
fun ArticleDto.toDomain(): Article = Article(
    id = articleId,
    title = title,
    summary = summarize(content),
    body = content,
)

/**
 * Derives a short preview from a full article [content], for the article list's summary line
 * (see [Article.summary]) when the API itself does not provide one.
 *
 * Prefers the first sentence (up to and including the first `.`, `!` or `?`), which usually reads
 * as a complete thought. If the content has no sentence boundary within a reasonable length, it
 * falls back to the first ~120 characters, trimmed back to the last whole word so the cut-off
 * never lands mid-word, followed by an ellipsis ("…").
 *
 * `internal` (rather than `private`): visible to this module's unit tests (see the module's test
 * source set), but still hidden from other modules — there is no reason for anything outside
 * `data.articles` to call this directly instead of going through [toDomain].
 */
internal fun summarize(content: String): String {
    val trimmed = content.trim()

    val sentenceEnd = trimmed.indexOfFirst { it == '.' || it == '!' || it == '?' }
    val maxSentenceLength = 160
    if (sentenceEnd in 0 until maxSentenceLength) {
        return trimmed.substring(0, sentenceEnd + 1).trim()
    }

    val maxPreviewLength = 120
    if (trimmed.length <= maxPreviewLength) return trimmed

    val cutoff = trimmed.substring(0, maxPreviewLength)
    // Back off to the last whole word so the preview never ends mid-word.
    val lastSpace = cutoff.lastIndexOf(' ')
    val wholeWordCutoff = if (lastSpace > 0) cutoff.substring(0, lastSpace) else cutoff
    return "$wholeWordCutoff…"
}
