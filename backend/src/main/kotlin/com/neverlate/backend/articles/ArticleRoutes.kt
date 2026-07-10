package com.neverlate.backend.articles

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** `GET /articles` — the API's first and only **public** route (contract.md §7): registered in
 *  [com.neverlate.backend.configureApp] **outside** any `authenticate("auth-jwt") { }` block,
 *  unlike every route in [com.neverlate.backend.tasks.taskRoutes]. Guest mode (feature 13) needs
 *  the article catalog to be readable with no account at all, and this is a global, read-only
 *  resource — there is nothing user-scoped to protect, so it never checks or even reads a token. */
fun Route.articleRoutes(articleService: ArticleService) {
    get("/articles") {
        val page = call.request.queryParameters["page"]
        val size = call.request.queryParameters["size"]
        call.respond(HttpStatusCode.OK, articleService.getPage(page, size))
    }
}
