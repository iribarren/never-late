package com.neverlate.backend.articles

/**
 * The repository seam for the article catalog — same pattern as
 * [com.neverlate.backend.tasks.TaskRepository], but **unscoped**: unlike tasks, articles are a
 * single global, read-only catalog rather than per-user data (contract.md §7 — `/articles` is the
 * API's first public endpoint, so there is no `userId` to scope by).
 */
interface ArticleRepository {
    /** One page of the catalog, ordered by the stable server-side `position` (contract.md §7's
     *  ordering guarantee) so pages never overlap or skip between calls. Returns fewer than
     *  [limit] items (down to an empty list) once [offset] runs past the end of the catalog. */
    fun page(offset: Int, limit: Int): List<ArticleDto>

    /** Total number of articles in the catalog, so a client can compute how many pages exist. */
    fun count(): Int
}
