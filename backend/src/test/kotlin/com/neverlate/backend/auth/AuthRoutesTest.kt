package com.neverlate.backend.auth

import com.neverlate.backend.configureApp
import com.neverlate.backend.jsonClient
import com.neverlate.backend.tasks.InMemoryTaskRepository
import com.neverlate.backend.testConfig
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** Covers acceptance criterion 1 (contract.md §2): register/login round-trip, wrong credentials
 *  -> 401, duplicate email -> 409, bad input -> 400, plus feature 12's `POST /auth/refresh` /
 *  `POST /auth/logout` (contract.md §2.1-§2.3, spec US-4/US-5): rotation, reuse-detected family
 *  kill, revocation, and idempotent/validated logout. */
class AuthRoutesTest {

    private suspend fun io.ktor.client.HttpClient.register(email: String, password: String) =
        post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(AuthRequest.serializer(), AuthRequest(email, password)))
        }

    private suspend fun io.ktor.client.HttpClient.login(email: String, password: String) =
        post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(AuthRequest.serializer(), AuthRequest(email, password)))
        }

    private suspend fun io.ktor.client.HttpClient.refresh(refreshToken: String) =
        post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken)))
        }

    private suspend fun io.ktor.client.HttpClient.logout(refreshToken: String) =
        post("/auth/logout") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(LogoutRequest.serializer(), LogoutRequest(refreshToken)))
        }

    @Test
    fun `register then login round-trip`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository()) }
        val client = jsonClient()

        val registerResponse = client.register("alice@example.com", "password123")
        assertEquals(HttpStatusCode.Created, registerResponse.status)
        val registered = Json.decodeFromString(AuthResponse.serializer(), registerResponse.bodyAsText())
        assertNotNull(registered.accessToken)
        assertNotNull(registered.refreshToken)
        assertEquals("alice@example.com", registered.user.email)

        val loginResponse = client.login("alice@example.com", "password123")
        assertEquals(HttpStatusCode.OK, loginResponse.status)
        val loggedIn = Json.decodeFromString(AuthResponse.serializer(), loginResponse.bodyAsText())
        assertNotNull(loggedIn.refreshToken)
        assertNotEquals(registered.refreshToken, loggedIn.refreshToken)
        assertEquals(registered.user.id, loggedIn.user.id)
    }

    @Test
    fun `register and a subsequent login start independent refresh-token families - killing one leaves the other valid`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository()) }
        val client = jsonClient()
        val registered = Json.decodeFromString(AuthResponse.serializer(), client.register("cora@example.com", "password123").bodyAsText())
        val loggedIn = Json.decodeFromString(AuthResponse.serializer(), client.login("cora@example.com", "password123").bodyAsText())

        // Kill the register session's family via reuse detection (rotate once, then replay the
        // now-stale token).
        client.refresh(registered.refreshToken)
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(registered.refreshToken).status)

        // The login session started its own family (spec decision #3: register and login both
        // start a NEW lineage) - it must be entirely unaffected by the register family's kill.
        val loginFamilyRefresh = client.refresh(loggedIn.refreshToken)
        assertEquals(HttpStatusCode.OK, loginFamilyRefresh.status)
    }

    @Test
    fun `duplicate email on register returns 409 email_taken`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository()) }
        val client = jsonClient()

        client.register("bob@example.com", "password123")
        val secondAttempt = client.register("bob@example.com", "differentPassword")

        assertEquals(HttpStatusCode.Conflict, secondAttempt.status)
        assertTrue(secondAttempt.bodyAsText().contains("email_taken"))
    }

    @Test
    fun `wrong password returns 401 invalid_credentials`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository()) }
        val client = jsonClient()

        client.register("carol@example.com", "correctPassword")
        val response = client.login("carol@example.com", "wrongPassword")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("invalid_credentials"))
    }

    @Test
    fun `unknown email returns the same 401 invalid_credentials as a wrong password`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository()) }
        val client = jsonClient()

        val response = client.login("nobody@example.com", "whatever123")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("invalid_credentials"))
    }

    @Test
    fun `password shorter than 8 chars is rejected with 400 validation_error`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository()) }
        val client = jsonClient()

        val response = client.register("dave@example.com", "short")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("validation_error"))
    }

    // POST /auth/refresh (contract.md §2.2) --------------------------------------------------------

    @Test
    fun `refresh returns a new access AND refresh token, and the old refresh token is rejected afterward`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository()) }
        val client = jsonClient()
        val registered = Json.decodeFromString(AuthResponse.serializer(), client.register("erin@example.com", "password123").bodyAsText())

        val refreshResponse = client.refresh(registered.refreshToken)

        assertEquals(HttpStatusCode.OK, refreshResponse.status)
        val rotated = Json.decodeFromString(AuthResponse.serializer(), refreshResponse.bodyAsText())
        // Not asserting accessToken != the old one: the JWT claims (userId/iss/aud/exp-to-the-
        // second) can coincidentally match if both are minted within the same second, since the
        // access token carries no per-issuance nonce (contract.md: it is stateless by design).
        // "New" here means freshly issued/valid, not necessarily textually different — the
        // *refresh* token (opaque, random per contract §2) is the one guaranteed to differ.
        assertNotNull(rotated.accessToken)
        assertNotEquals(registered.refreshToken, rotated.refreshToken)
        assertEquals(registered.user.id, rotated.user.id)

        // The rotated-away token can never be used again (contract.md §2.1 "Rotation").
        val replay = client.refresh(registered.refreshToken)
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        assertTrue(replay.bodyAsText().contains("invalid_refresh_token"))
    }

    @Test
    fun `blank refreshToken on refresh returns 400 validation_error`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository()) }
        val client = jsonClient()

        val response = client.refresh("")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("validation_error"))
    }

    @Test
    fun `an unknown refresh token is rejected with 401 invalid_refresh_token`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository()) }
        val client = jsonClient()

        val response = client.refresh("this-token-was-never-issued")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("invalid_refresh_token"))
    }

    @Test
    fun `reusing an already-rotated refresh token kills the whole family, including its freshly-rotated sibling`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository()) }
        val client = jsonClient()
        val registered = Json.decodeFromString(AuthResponse.serializer(), client.register("frank@example.com", "password123").bodyAsText())
        val r0 = registered.refreshToken

        // Legitimate rotation: r0 -> r1. r1 is a live, valid sibling in the same family.
        val firstRefresh = Json.decodeFromString(AuthResponse.serializer(), client.refresh(r0).bodyAsText())
        val r1 = firstRefresh.refreshToken

        // Reuse/theft: r0 is presented again even though it was already consumed above.
        val reuseAttempt = client.refresh(r0)
        assertEquals(HttpStatusCode.Unauthorized, reuseAttempt.status)
        assertTrue(reuseAttempt.bodyAsText().contains("invalid_refresh_token"))

        // The whole family is now dead (contract.md §2.1): r1, which was valid a moment ago, must
        // also fail now, even though it was never itself replayed.
        val siblingAttempt = client.refresh(r1)
        assertEquals(HttpStatusCode.Unauthorized, siblingAttempt.status)
        assertTrue(siblingAttempt.bodyAsText().contains("invalid_refresh_token"))
    }

    @Test
    fun `an independent family (a separate login) survives another family's reuse-triggered kill`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository()) }
        val client = jsonClient()
        client.register("grace@example.com", "password123")
        // Two independent sessions/families for the same user: the register call itself, and a
        // separate login (e.g. a second device).
        val session1 = Json.decodeFromString(AuthResponse.serializer(), client.login("grace@example.com", "password123").bodyAsText())
        val session2 = Json.decodeFromString(AuthResponse.serializer(), client.login("grace@example.com", "password123").bodyAsText())

        // Rotate session1 once, then replay the now-stale token to trigger a family kill.
        client.refresh(session1.refreshToken)
        val reuseAttempt = client.refresh(session1.refreshToken)
        assertEquals(HttpStatusCode.Unauthorized, reuseAttempt.status)

        // session2's family is untouched.
        val otherFamilyRefresh = client.refresh(session2.refreshToken)
        assertEquals(HttpStatusCode.OK, otherFamilyRefresh.status)
    }

    // POST /auth/logout (contract.md §2.3) ---------------------------------------------------------

    @Test
    fun `logout revokes the refresh token - subsequent refresh with it returns 401`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository()) }
        val client = jsonClient()
        val registered = Json.decodeFromString(AuthResponse.serializer(), client.register("henry@example.com", "password123").bodyAsText())

        val logoutResponse = client.logout(registered.refreshToken)
        assertEquals(HttpStatusCode.NoContent, logoutResponse.status)

        val refreshAfterLogout = client.refresh(registered.refreshToken)
        assertEquals(HttpStatusCode.Unauthorized, refreshAfterLogout.status)
        assertTrue(refreshAfterLogout.bodyAsText().contains("invalid_refresh_token"))
    }

    @Test
    fun `logout is idempotent - an unknown or already-revoked token still returns 204`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository()) }
        val client = jsonClient()

        val unknownTokenLogout = client.logout("never-issued-token")
        assertEquals(HttpStatusCode.NoContent, unknownTokenLogout.status)

        val registered = Json.decodeFromString(AuthResponse.serializer(), client.register("iris@example.com", "password123").bodyAsText())
        assertEquals(HttpStatusCode.NoContent, client.logout(registered.refreshToken).status)
        // Logging out the same, already-revoked token again must still be 204 (never leaks state).
        assertEquals(HttpStatusCode.NoContent, client.logout(registered.refreshToken).status)
    }

    @Test
    fun `blank refreshToken on logout returns 400 validation_error`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository(), InMemoryRefreshTokenRepository()) }
        val client = jsonClient()

        val response = client.logout("")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("validation_error"))
    }

    private fun assertTrue(condition: Boolean) = org.junit.jupiter.api.Assertions.assertTrue(condition)
}
