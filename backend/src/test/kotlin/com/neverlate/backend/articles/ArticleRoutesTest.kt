package com.neverlate.backend.articles

import com.neverlate.backend.auth.InMemoryRefreshTokenRepository
import com.neverlate.backend.auth.InMemoryUserRepository
import com.neverlate.backend.configureApp
import com.neverlate.backend.jsonClient
import com.neverlate.backend.tasks.InMemoryTaskRepository
import com.neverlate.backend.testConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Covers US-5 / acceptance criterion 8 (spec `docs/specs/2026-07-10-articles-paging.md`,
 *  contract.md §7): `GET /articles` is public, paginated, clamps `size`, validates `page`, and
 *  returns items in a stable order. Every test wires a fresh, small, known
 *  [InMemoryArticleRepository] — hermetic, no Docker/Postgres needed. */
class ArticleRoutesTest {

    /** Five articles is enough to exercise a page boundary (default size 20 still fits them all
     *  in one page) while `size=2`/`size=3` queries below produce more than one page. */
    private fun catalog(): List<ArticleDto> = (1..5).map { n ->
        ArticleDto(articleId = "article-$n", title = "Title $n", content = "Content $n")
    }

    private suspend fun HttpClient.getArticles(query: String = ""): io.ktor.client.statement.HttpResponse =
        get("/articles$query")

    @Test
    fun `first page returns items, page, size and total`() = testApplication {
        application {
            configureApp(
                testConfig(),
                InMemoryUserRepository(),
                InMemoryTaskRepository(),
                InMemoryRefreshTokenRepository(),
                InMemoryArticleRepository(catalog()),
            )
        }
        val client = jsonClient()

        val response = client.getArticles("?page=0&size=2")

        assertEquals(HttpStatusCode.OK, response.status)
        val page = Json.decodeFromString(ArticlesPage.serializer(), response.bodyAsText())
        assertEquals(2, page.items.size)
        assertEquals(listOf("article-1", "article-2"), page.items.map { it.articleId })
        assertEquals(0, page.page)
        assertEquals(2, page.size)
        assertEquals(5, page.total)
    }

    @Test
    fun `default page and size are used when omitted`() = testApplication {
        application {
            configureApp(
                testConfig(),
                InMemoryUserRepository(),
                InMemoryTaskRepository(),
                InMemoryRefreshTokenRepository(),
                InMemoryArticleRepository(catalog()),
            )
        }
        val client = jsonClient()

        val page = Json.decodeFromString(ArticlesPage.serializer(), client.getArticles().bodyAsText())

        assertEquals(0, page.page)
        assertEquals(20, page.size) // default size, even though the catalog only has 5 rows
        assertEquals(5, page.total)
        assertEquals(5, page.items.size) // the whole catalog fits in one default-sized page
    }

    @Test
    fun `size above the max is clamped to 100`() = testApplication {
        application {
            configureApp(
                testConfig(),
                InMemoryUserRepository(),
                InMemoryTaskRepository(),
                InMemoryRefreshTokenRepository(),
                InMemoryArticleRepository(catalog()),
            )
        }
        val client = jsonClient()

        val page = Json.decodeFromString(ArticlesPage.serializer(), client.getArticles("?size=1000").bodyAsText())

        assertEquals(100, page.size) // clamped, not rejected
    }

    @Test
    fun `size of zero or negative is clamped up to the minimum of 1`() = testApplication {
        application {
            configureApp(
                testConfig(),
                InMemoryUserRepository(),
                InMemoryTaskRepository(),
                InMemoryRefreshTokenRepository(),
                InMemoryArticleRepository(catalog()),
            )
        }
        val client = jsonClient()

        val zero = Json.decodeFromString(ArticlesPage.serializer(), client.getArticles("?size=0").bodyAsText())
        assertEquals(1, zero.size)
        assertEquals(1, zero.items.size)

        val negative = Json.decodeFromString(ArticlesPage.serializer(), client.getArticles("?size=-5").bodyAsText())
        assertEquals(1, negative.size)
    }

    @Test
    fun `page past the end returns an empty items list, not an error`() = testApplication {
        application {
            configureApp(
                testConfig(),
                InMemoryUserRepository(),
                InMemoryTaskRepository(),
                InMemoryRefreshTokenRepository(),
                InMemoryArticleRepository(catalog()),
            )
        }
        val client = jsonClient()

        val page = Json.decodeFromString(ArticlesPage.serializer(), client.getArticles("?page=99&size=2").bodyAsText())

        assertTrue(page.items.isEmpty())
        assertEquals(99, page.page)
        assertEquals(5, page.total)
    }

    @Test
    fun `no Authorization header still returns 200 - the endpoint is public`() = testApplication {
        application {
            configureApp(
                testConfig(),
                InMemoryUserRepository(),
                InMemoryTaskRepository(),
                InMemoryRefreshTokenRepository(),
                InMemoryArticleRepository(catalog()),
            )
        }
        val client = jsonClient()

        val response = client.getArticles()

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `a negative page is rejected with 400 validation_error`() = testApplication {
        application {
            configureApp(
                testConfig(),
                InMemoryUserRepository(),
                InMemoryTaskRepository(),
                InMemoryRefreshTokenRepository(),
                InMemoryArticleRepository(catalog()),
            )
        }
        val client = jsonClient()

        val response = client.getArticles("?page=-1")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("validation_error"))
    }

    @Test
    fun `a non-numeric page is rejected with 400 validation_error`() = testApplication {
        application {
            configureApp(
                testConfig(),
                InMemoryUserRepository(),
                InMemoryTaskRepository(),
                InMemoryRefreshTokenRepository(),
                InMemoryArticleRepository(catalog()),
            )
        }
        val client = jsonClient()

        val response = client.getArticles("?page=abc")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("validation_error"))
    }

    @Test
    fun `a non-numeric size is rejected with 400 validation_error`() = testApplication {
        application {
            configureApp(
                testConfig(),
                InMemoryUserRepository(),
                InMemoryTaskRepository(),
                InMemoryRefreshTokenRepository(),
                InMemoryArticleRepository(catalog()),
            )
        }
        val client = jsonClient()

        val response = client.getArticles("?size=abc")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("validation_error"))
    }

    @Test
    fun `pages never overlap or skip - a stable order across two page requests`() = testApplication {
        application {
            configureApp(
                testConfig(),
                InMemoryUserRepository(),
                InMemoryTaskRepository(),
                InMemoryRefreshTokenRepository(),
                InMemoryArticleRepository(catalog()),
            )
        }
        val client = jsonClient()

        val firstPage = Json.decodeFromString(ArticlesPage.serializer(), client.getArticles("?page=0&size=3").bodyAsText())
        val secondPage = Json.decodeFromString(ArticlesPage.serializer(), client.getArticles("?page=1&size=3").bodyAsText())

        assertEquals(listOf("article-1", "article-2", "article-3"), firstPage.items.map { it.articleId })
        assertEquals(listOf("article-4", "article-5"), secondPage.items.map { it.articleId })
        val overlap = firstPage.items.map { it.articleId }.toSet().intersect(secondPage.items.map { it.articleId }.toSet())
        assertTrue(overlap.isEmpty())
    }

    @Test
    fun `an empty catalog returns 200 with an empty items list`() = testApplication {
        application {
            configureApp(
                testConfig(),
                InMemoryUserRepository(),
                InMemoryTaskRepository(),
                InMemoryRefreshTokenRepository(),
                InMemoryArticleRepository(emptyList()),
            )
        }
        val client = jsonClient()

        val response = client.getArticles()

        assertEquals(HttpStatusCode.OK, response.status)
        val page = Json.decodeFromString(ArticlesPage.serializer(), response.bodyAsText())
        assertTrue(page.items.isEmpty())
        assertEquals(0, page.total)
        assertFalse(response.bodyAsText().contains("\"error\""))
    }
}
