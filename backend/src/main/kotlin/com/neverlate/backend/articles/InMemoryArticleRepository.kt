package com.neverlate.backend.articles

/** Test fake for [ArticleRepository] — same rationale as
 *  [com.neverlate.backend.tasks.InMemoryTaskRepository]: fast, hermetic route tests with no
 *  Postgres/Docker involved. Takes its catalog at construction time (in the order it should be
 *  paged, mirroring the Postgres `position` column) rather than seeding from the bundled JSON —
 *  tests want a small, known catalog, not the full production one. */
class InMemoryArticleRepository(seed: List<ArticleDto> = emptyList()) : ArticleRepository {
    private val articles: List<ArticleDto> = seed.toList()

    override fun page(offset: Int, limit: Int): List<ArticleDto> =
        if (offset >= articles.size) emptyList() else articles.drop(offset).take(limit)

    override fun count(): Int = articles.size
}
