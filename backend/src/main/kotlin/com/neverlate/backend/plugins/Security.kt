package com.neverlate.backend.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.neverlate.backend.Config
import com.neverlate.backend.common.ErrorBody
import com.neverlate.backend.common.ErrorEnvelope
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond

/**
 * Installs Ktor's `jwt` auth provider under the name "auth-jwt" (referenced by
 * `authenticate("auth-jwt") { ... }` in tasks/TaskRoutes.kt). Verification is purely signature +
 * expiry — stateless, matching the "no refresh token in v1" decision in the spec: there is no
 * server-side session to check against, so a compromised secret or a leaked token can't be
 * revoked early, only allowed to expire.
 */
fun Application.configureSecurity(config: Config) {
    install(Authentication) {
        jwt("auth-jwt") {
            realm = config.jwtIssuer
            verifier(
                JWT.require(Algorithm.HMAC256(config.jwtSecret))
                    .withIssuer(config.jwtIssuer)
                    .withAudience(config.jwtAudience)
                    .build(),
            )
            validate { credential ->
                // The verifier above already checked signature/issuer/audience/expiry; this just
                // confirms our own claim is present before trusting it as a user id.
                if (credential.payload.getClaim("userId").asLong() != null) JWTPrincipal(credential.payload) else null
            }
            // Any missing/invalid/expired token lands here — contract.md §1.2: "A missing/
            // invalid/expired token -> 401 { error.code: "unauthorized" }".
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorEnvelope(ErrorBody(code = "unauthorized", message = "Missing or invalid token")),
                )
            }
        }
    }
}
