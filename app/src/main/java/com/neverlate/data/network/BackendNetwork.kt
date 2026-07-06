package com.neverlate.data.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.neverlate.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

/**
 * Where this project's own backend (`backend/`, feature 11) listens. `10.0.2.2` is the special
 * address the Android **emulator** uses to reach the host machine's `localhost` — see
 * `docs/api/contract.md`. A physical device on the same network would need the host's real LAN IP
 * instead; that override is exactly what every `create(baseUrl = ...)` parameter below is for
 * (tests point it at a local `MockWebServer` the same way, following feature 10's
 * [com.neverlate.data.articles.ArticlesNetwork] precedent).
 */
const val DEFAULT_BACKEND_BASE_URL = "http://10.0.2.2:8080/"

/**
 * Shared Retrofit wiring for this app's own backend (feature 11), used by both
 * [com.neverlate.data.auth.AuthNetwork] (no auth needed — you can't attach a Bearer token before
 * you have one) and [TasksNetwork] (every call needs one, via [extraInterceptors]). Factored out
 * once here — rather than duplicated per API the way [com.neverlate.data.articles.ArticlesNetwork]
 * stands alone for the articles endpoint — since both auth and tasks talk to the *same* backend
 * and the only real difference between them is which interceptors apply.
 */
object BackendNetwork {
    fun <T> create(
        service: Class<T>,
        baseUrl: String = DEFAULT_BACKEND_BASE_URL,
        extraInterceptors: List<Interceptor> = emptyList(),
    ): T {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
            // Redact the bearer JWT even in debug logs: BODY-level logging is invaluable for
            // development, but a raw token in logcat can end up in bug reports/crash captures
            // shared outside the device. redactHeader prints "Authorization: ██" instead of the
            // real value, so the header's presence is still visible without leaking the secret.
            redactHeader("Authorization")
        }

        val clientBuilder = OkHttpClient.Builder()
        // Extra interceptors (e.g. AuthInterceptor) must run before the logging one, so a
        // request's final Authorization header (if any) shows up in the debug log too.
        extraInterceptors.forEach { clientBuilder.addInterceptor(it) }
        clientBuilder.addInterceptor(loggingInterceptor)

        val json = Json { ignoreUnknownKeys = true }

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(service)
    }
}
