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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** Covers acceptance criterion 1 (contract.md §2): register/login round-trip, wrong credentials
 *  -> 401, duplicate email -> 409, bad input -> 400. */
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

    @Test
    fun `register then login round-trip`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository()) }
        val client = jsonClient()

        val registerResponse = client.register("alice@example.com", "password123")
        assertEquals(HttpStatusCode.Created, registerResponse.status)
        val registered = Json.decodeFromString(AuthResponse.serializer(), registerResponse.bodyAsText())
        assertNotNull(registered.token)
        assertEquals("alice@example.com", registered.user.email)

        val loginResponse = client.login("alice@example.com", "password123")
        assertEquals(HttpStatusCode.OK, loginResponse.status)
        val loggedIn = Json.decodeFromString(AuthResponse.serializer(), loginResponse.bodyAsText())
        assertEquals(registered.user.id, loggedIn.user.id)
    }

    @Test
    fun `duplicate email on register returns 409 email_taken`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository()) }
        val client = jsonClient()

        client.register("bob@example.com", "password123")
        val secondAttempt = client.register("bob@example.com", "differentPassword")

        assertEquals(HttpStatusCode.Conflict, secondAttempt.status)
        assertTrue(secondAttempt.bodyAsText().contains("email_taken"))
    }

    @Test
    fun `wrong password returns 401 invalid_credentials`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository()) }
        val client = jsonClient()

        client.register("carol@example.com", "correctPassword")
        val response = client.login("carol@example.com", "wrongPassword")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("invalid_credentials"))
    }

    @Test
    fun `unknown email returns the same 401 invalid_credentials as a wrong password`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository()) }
        val client = jsonClient()

        val response = client.login("nobody@example.com", "whatever123")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("invalid_credentials"))
    }

    @Test
    fun `password shorter than 8 chars is rejected with 400 validation_error`() = testApplication {
        application { configureApp(testConfig(), InMemoryUserRepository(), InMemoryTaskRepository()) }
        val client = jsonClient()

        val response = client.register("dave@example.com", "short")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("validation_error"))
    }

    private fun assertTrue(condition: Boolean) = org.junit.jupiter.api.Assertions.assertTrue(condition)
}
