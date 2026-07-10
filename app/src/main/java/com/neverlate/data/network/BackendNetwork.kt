package com.neverlate.data.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.neverlate.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

/**
 * Where this project's own backend (`backend/`, feature 11) listens. Read from
 * [BuildConfig.BACKEND_BASE_URL], which `app/build.gradle.kts` injects from a
 * `neverlate.backendBaseUrl` property in `local.properties` (untracked — never committed),
 * defaulting to `http://10.0.2.2:8080/` when that property is absent.
 *
 * `10.0.2.2` is the special address the Android **emulator** uses to reach the host machine's
 * `localhost` — see `docs/api/contract.md`. A **physical device** doesn't have that alias, so it
 * needs `local.properties` set to either `http://localhost:8080/` (with `adb reverse tcp:8080
 * tcp:8080` forwarding the phone's `localhost` to the host, recommended) or the host's real LAN
 * IP (see `CLAUDE.md` → Development → Backend → "Testing on a physical device"). Either way, the
 * `create(baseUrl = ...)` parameter below is the seam that lets tests point this at a local
 * `MockWebServer` instead, following feature 10's [com.neverlate.data.articles.ArticlesNetwork]
 * precedent.
 */
const val DEFAULT_BACKEND_BASE_URL = BuildConfig.BACKEND_BASE_URL

/**
 * Shared Retrofit wiring for this app's own backend (feature 11), used by both
 * [com.neverlate.data.auth.AuthNetwork] (no auth needed — you can't attach a Bearer token before
 * you have one) and [TasksNetwork] (every call needs one, via [extraInterceptors]). Factored out
 * once here — rather than duplicated per API the way [com.neverlate.data.articles.ArticlesNetwork]
 * stands alone for the articles endpoint — since both auth and tasks talk to the *same* backend
 * and the only real difference between them is which interceptors/authenticator apply.
 */
object BackendNetwork {
    fun <T> create(
        service: Class<T>,
        baseUrl: String = DEFAULT_BACKEND_BASE_URL,
        extraInterceptors: List<Interceptor> = emptyList(),
        authenticator: Authenticator? = null,
        // Feature 12 security fix: unlike task bodies (not secret), the `/auth/*` request/response
        // bodies carry the password (register/login) and both tokens (register/login/refresh) in
        // clear JSON. `redactHeader("Authorization")` below only strips the *header* — it does
        // nothing for the body — so logging those bodies at BODY level would print a live refresh
        // token straight to Logcat in every debug build, which can leak into bug reports/crash
        // captures shared outside the device (the exact risk redactHeader exists to avoid, just for
        // the header instead of the body). [com.neverlate.data.auth.AuthNetwork] passes `false` here;
        // [TasksNetwork] keeps the default so task bodies remain fully visible for debugging.
        logBodies: Boolean = true,
    ): T {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = when {
                !BuildConfig.DEBUG -> HttpLoggingInterceptor.Level.NONE
                logBodies -> HttpLoggingInterceptor.Level.BODY
                // BASIC logs only method/URL/status/timing — no headers, no body — so auth calls
                // still show up in the debug log for troubleshooting without ever printing a
                // password or token.
                else -> HttpLoggingInterceptor.Level.BASIC
            }
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
        // The authenticator (e.g. TokenAuthenticator) is a separate OkHttp extension point from
        // interceptors — see that class's KDoc — attached only when the caller passes one in
        // (AuthNetwork never does, precisely so the refresh call itself can't recurse into it).
        authenticator?.let { clientBuilder.authenticator(it) }

        // coerceInputValues (feature 13b): if the server ever sends a `priority` this client no
        // longer knows (or a plain `null`), decode it as the property's default (Priority.NONE)
        // instead of throwing — the forward-compat behaviour the API contract §5 promises. Pairs
        // with the tolerant Room converter so an unknown priority degrades safely on both the wire
        // and on disk.
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(service)
    }
}
