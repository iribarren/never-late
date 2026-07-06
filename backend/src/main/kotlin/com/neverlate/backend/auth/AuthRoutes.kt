package com.neverlate.backend.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** `POST /auth/register` and `POST /auth/login` (contract.md §2) — the only two unauthenticated
 *  endpoints in the API; everything under /tasks requires the Bearer token these return. */
fun Route.authRoutes(authService: AuthService) {
    post("/auth/register") {
        val request = call.receive<AuthRequest>()
        val response = authService.register(request.email, request.password)
        call.respond(HttpStatusCode.Created, response)
    }

    post("/auth/login") {
        val request = call.receive<AuthRequest>()
        val response = authService.login(request.email, request.password)
        call.respond(HttpStatusCode.OK, response)
    }
}
