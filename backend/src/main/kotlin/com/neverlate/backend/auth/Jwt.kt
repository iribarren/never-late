package com.neverlate.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.neverlate.backend.Config
import java.util.Date

/**
 * Stateless JWT issuing + the claim name both the issuing code and the auth plugin (see
 * plugins/Security.kt) need to agree on. "Stateless" means the server verifies a token purely by
 * its signature + expiry — no server-side session table, no way to revoke early in v1 (per the
 * spec: no refresh token, a 401 just sends the client back to login).
 */
object Jwt {
    const val USER_ID_CLAIM = "userId"

    fun createToken(config: Config, userId: Long): String {
        val expiresAt = Date(System.currentTimeMillis() + config.jwtExpiryHours * 60 * 60 * 1000)
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withClaim(USER_ID_CLAIM, userId)
            .withExpiresAt(expiresAt)
            .sign(Algorithm.HMAC256(config.jwtSecret))
    }
}
