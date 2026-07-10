package com.neverlate.backend.articles

import java.sql.ResultSet
import javax.sql.DataSource

/** Plain JDBC, same style as [com.neverlate.backend.tasks.PostgresTaskRepository] — no ORM (see
 *  db/Database.kt for why). Ordering by `position` (assigned at seed time, db/Database.kt) is
 *  what gives `GET /articles` its stable total order across pages. */
class PostgresArticleRepository(private val dataSource: DataSource) : ArticleRepository {

    override fun page(offset: Int, limit: Int): List<ArticleDto> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT article_id, title, content FROM articles ORDER BY position ASC LIMIT ? OFFSET ?",
            ).use { stmt ->
                stmt.setInt(1, limit)
                stmt.setInt(2, offset)
                stmt.executeQuery().use { rs ->
                    val results = mutableListOf<ArticleDto>()
                    while (rs.next()) results.add(rs.toArticleDto())
                    results
                }
            }
        }

    override fun count(): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT count(*) FROM articles").use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun ResultSet.toArticleDto() = ArticleDto(
        articleId = getString("article_id"),
        title = getString("title"),
        content = getString("content"),
    )
}
