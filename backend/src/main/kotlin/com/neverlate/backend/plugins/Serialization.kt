package com.neverlate.backend.plugins

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json

/** All request/response bodies are `application/json` (contract.md, top). This is the same
 *  kotlinx.serialization library the Android client already uses for its DTOs (feature 10),
 *  just wired into Ktor's server-side content negotiation instead of a Retrofit converter. */
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
        )
    }
}
