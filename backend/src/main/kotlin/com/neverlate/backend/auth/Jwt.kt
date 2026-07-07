package com.neverlate.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.neverlate.backend.Config
import java.util.Date

/**
 * Stateless JWT issuing + the claim name both the issuing code and the auth plugin (see
 * plugins/Security.kt) need to agree on. "Stateless" means the server verifies a token purely by
 * its signature + expiry — no server-side session table for the access token itself, no way to
 * revoke one early.
 *
 * Feature 12 deliberately keeps that trade-off but shrinks its blast radius: the access token's
 * lifetime is now minutes, not hours, so a leaked token is only useful briefly. Early revocation
 * and multi-day sessions move to the *refresh* token instead, which the server does track
 * server-side (see [com.neverlate.backend.auth.RefreshTokenRepository]) precisely because it is
 * long-lived enough that "just let it expire" is not an acceptable security story on its own.
 */
object Jwt {
    const val USER_ID_CLAIM = "userId"

    fun createToken(config: Config, userId: Long): String {
        val expiresAt = Date(System.currentTimeMillis() + config.accessTokenExpiryMinutes * 60 * 1000)
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withClaim(USER_ID_CLAIM, userId)
            .withExpiresAt(expiresAt)
            .sign(Algorithm.HMAC256(config.jwtSecret))
    }
}
