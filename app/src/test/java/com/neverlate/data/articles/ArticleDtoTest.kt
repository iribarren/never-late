package com.neverlate.data.articles

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the wire-to-domain mapping this feature introduces: [ArticleDto.toDomain]
 * (field renames + derived [Article.summary]) and the [summarize] helper it delegates to. No
 * network, Room, or Android runtime involved — these are plain functions on plain data classes.
 */
class ArticleDtoTest {

    @Test
    fun `toDomain renames article_id to id and content to body`() {
        val dto = ArticleDto(
            articleId = "pomodoro",
            title = "La técnica Pomodoro",
            content = "Un texto corto sin puntuación final",
        )

        val article = dto.toDomain()

        assertEquals("pomodoro", article.id)
        assertEquals("La técnica Pomodoro", article.title)
        assertEquals("Un texto corto sin puntuación final", article.body)
    }

    @Test
    fun `toDomain derives summary from content via summarize`() {
        val dto = ArticleDto(articleId = "id", title = "T", content = "Frase corta. Resto ignorado.")

        val article = dto.toDomain()

        assertEquals(summarize(dto.content), article.summary)
        assertEquals("Frase corta.", article.summary)
    }

    @Test
    fun `ArticleDto deserializes the actual snake_case wire shape via SerialName`() {
        // The real shape the API sends (see docs/articles-api/articles.json): snake_case
        // "article_id", and no "summary" field at all.
        val json = Json { ignoreUnknownKeys = true }
        val wire = """{"article_id": "pomodoro", "title": "La técnica Pomodoro", "content": "Contenido."}"""

        val dto = json.decodeFromString(ArticleDto.serializer(), wire)

        assertEquals("pomodoro", dto.articleId)
        assertEquals("La técnica Pomodoro", dto.title)
        assertEquals("Contenido.", dto.content)
    }

    @Test
    fun `ArticleDto deserialization ignores unknown keys like a future 'author' field`() {
        val json = Json { ignoreUnknownKeys = true }
        val wire = """{"article_id": "id", "title": "T", "content": "C", "author": "Someone"}"""

        val dto = json.decodeFromString(ArticleDto.serializer(), wire)

        assertEquals("id", dto.articleId)
    }

    // --- summarize(): short text -------------------------------------------------------------

    @Test
    fun `summarize returns short text as-is when there is no sentence end and it fits the preview length`() {
        val text = "Texto corto sin punto final"

        assertEquals(text, summarize(text))
    }

    @Test
    fun `summarize returns up to and including the first sentence-ending period`() {
        val text = "Frase corta. Resto del contenido que no debería aparecer en el resumen."

        assertEquals("Frase corta.", summarize(text))
    }

    @Test
    fun `summarize recognizes question and exclamation marks as sentence ends too`() {
        assertEquals("¿Cuál es la clave?", summarize("¿Cuál es la clave? Más texto después."))
        assertEquals("Empieza ya!", summarize("Empieza ya! Más texto después."))
    }

    @Test
    fun `summarize trims surrounding whitespace before and after finding the sentence end`() {
        val text = "   Frase con espacios.   Resto.   "

        assertEquals("Frase con espacios.", summarize(text))
    }

    // --- summarize(): long text, no early sentence end ----------------------------------------

    @Test
    fun `summarize truncates long text at a word boundary and appends an ellipsis`() {
        val word = "palabra"
        val text = (1..30).joinToString(" ") { word } // no punctuation; well over 120 chars

        val result = summarize(text)

        assertTrue(result.endsWith("…"))
        assertFalse(result.contains("  "))
        val withoutEllipsis = result.removeSuffix("…")
        // Every token that survived the cut is a whole "palabra" — none was chopped mid-word.
        assertTrue(withoutEllipsis.split(" ").all { it == word })
    }

    @Test
    fun `summarize falls back to a hard cutoff when the first token alone exceeds the preview length`() {
        val text = "a".repeat(200) // one giant "word" with no spaces at all

        val result = summarize(text)

        assertEquals("${"a".repeat(120)}…", result)
    }

    @Test
    fun `summarize ignores a sentence end beyond the max sentence length and falls back to word-boundary truncation`() {
        // The first '.' lands at index 165 (>= the 160 cutoff), so the sentence-boundary branch
        // must NOT be used — the word-boundary/ellipsis path should kick in instead.
        val text = "${"x".repeat(165)}. resto"

        val result = summarize(text)

        assertFalse(result.endsWith("."))
        assertTrue(result.endsWith("…"))
    }

    // --- summarize(): edge cases ---------------------------------------------------------------

    @Test
    fun `summarize on empty content returns an empty string`() {
        assertEquals("", summarize(""))
    }

    @Test
    fun `summarize on blank content returns an empty string`() {
        assertEquals("", summarize("   \n\t  "))
    }
}
