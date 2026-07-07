package com.neverlate.backend.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** `POST /auth/register`, `/auth/login`, `/auth/refresh`, and `/auth/logout` (contract.md §2) —
 *  the only unauthenticated endpoints in the API (none of them read the `Authorization` header);
 *  everything under /tasks requires the Bearer access token these mint. */
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

    post("/auth/refresh") {
        val request = call.receive<RefreshRequest>()
        val response = authService.refresh(request.refreshToken)
        call.respond(HttpStatusCode.OK, response)
    }

    post("/auth/logout") {
        val request = call.receive<LogoutRequest>()
        authService.revoke(request.refreshToken)
        // 204 unconditionally (contract.md §2.3): logout is idempotent and must not leak whether
        // the presented token was valid, unknown, or already revoked.
        call.respond(HttpStatusCode.NoContent)
    }
}
