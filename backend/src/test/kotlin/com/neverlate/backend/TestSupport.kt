package com.neverlate.backend

import com.neverlate.backend.auth.InMemoryUserRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json

/** A [Config] with placeholder DB credentials — safe because every test wires
 *  [InMemoryUserRepository] / `InMemoryTaskRepository` instead of a real Postgres connection, so
 *  those fields are never actually used to open a connection. Only the JWT fields matter here. */
fun testConfig() = Config(
    port = 0,
    jwtSecret = "test-only-secret-do-not-use-in-prod",
    jwtIssuer = "never-late-backend-test",
    jwtAudience = "never-late-app-test",
    jwtExpiryHours = 1,
    databaseUrl = "unused-in-tests",
    databaseUser = "unused-in-tests",
    databasePassword = "unused-in-tests",
)

/** A JSON-aware test client — mirrors the real app's ContentNegotiation config so response
 *  bodies can be decoded straight into the same DTOs the server uses. */
fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
