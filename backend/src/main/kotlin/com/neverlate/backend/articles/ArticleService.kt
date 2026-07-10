package com.neverlate.backend.articles

import com.neverlate.backend.common.ValidationException

/**
 * Business logic for `GET /articles` (contract.md §7): parses/validates the raw `page`/`size`
 * query strings and assembles the paged response. Depends only on [ArticleRepository], so it's
 * tested against [InMemoryArticleRepository] with no real DB — the same shape as
 * [com.neverlate.backend.tasks.TaskService].
 */
class ArticleService(private val articles: ArticleRepository) {

    fun getPage(pageParam: String?, sizeParam: String?): ArticlesPage {
        val page = parsePage(pageParam)
        val size = parseSize(sizeParam)

        val offset = page * size
        val items = articles.page(offset = offset, limit = size)
        return ArticlesPage(items = items, page = page, size = size, total = articles.count())
    }

    /** `page` is zero-based and must never be negative (contract.md §7). Unlike `size`, an
     *  out-of-range `page` is rejected rather than clamped: a client asking for page `-1` almost
     *  certainly has a bug worth surfacing, whereas "too many/too few items per page" is a
     *  harmless request to just bring back into bounds. */
    private fun parsePage(raw: String?): Int {
        if (raw.isNullOrBlank()) return DEFAULT_PAGE
        val value = raw.toIntOrNull() ?: throw ValidationException("page must be a number")
        if (value < 0) throw ValidationException("page must not be negative")
        return value
    }

    /** `size` is always clamped into `[MIN_SIZE, MAX_SIZE]` rather than rejected (contract.md
     *  §7) — only a non-numeric value is a hard `400`; an out-of-range one (`0`, `-5`, `1000`) is
     *  simply brought back into bounds and `size` in the response reflects the value actually
     *  used. */
    private fun parseSize(raw: String?): Int {
        if (raw.isNullOrBlank()) return DEFAULT_SIZE
        val value = raw.toIntOrNull() ?: throw ValidationException("size must be a number")
        return value.coerceIn(MIN_SIZE, MAX_SIZE)
    }

    private companion object {
        const val DEFAULT_PAGE = 0
        const val DEFAULT_SIZE = 20
        const val MIN_SIZE = 1
        const val MAX_SIZE = 100
    }
}
