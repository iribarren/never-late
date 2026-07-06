package com.neverlate.data.articles

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the [Article] <-> [ArticleEntity] mapping [CachingArticleRepository] relies
 * on to read/write the Room cache. Unlike [ArticleDto.toDomain], this mapping is a trivial
 * one-to-one field copy (see [ArticleEntity]'s KDoc) — these tests pin that down explicitly and
 * guard against the two shapes silently drifting apart.
 */
class ArticleEntityTest {

    private val article = Article(
        id = "pomodoro",
        title = "La técnica Pomodoro",
        summary = "Resumen breve.",
        body = "Cuerpo completo del artículo sobre Pomodoro.",
    )

    @Test
    fun `Article toEntity copies every field one-to-one`() {
        val entity = article.toEntity()

        assertEquals(article.id, entity.id)
        assertEquals(article.title, entity.title)
        assertEquals(article.summary, entity.summary)
        assertEquals(article.body, entity.body)
    }

    @Test
    fun `ArticleEntity toDomain copies every field one-to-one`() {
        val entity = ArticleEntity(
            id = "time-blocking",
            title = "Time blocking",
            summary = "Resumen.",
            body = "Cuerpo.",
        )

        val domain = entity.toDomain()

        assertEquals(entity.id, domain.id)
        assertEquals(entity.title, domain.title)
        assertEquals(entity.summary, domain.summary)
        assertEquals(entity.body, domain.body)
    }

    @Test
    fun `an Article survives a toEntity then toDomain round trip unchanged`() {
        assertEquals(article, article.toEntity().toDomain())
    }
}
