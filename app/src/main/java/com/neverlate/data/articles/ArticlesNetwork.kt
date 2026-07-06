package com.neverlate.data.articles

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.neverlate.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

/** Where the remote article catalog is hosted. See [create]'s KDoc for the full URL story. */
private const val DEFAULT_BASE_URL =
    "https://raw.githubusercontent.com/iribarren/never-late/master/docs/articles-api/"

/**
 * Builds a ready-to-use [ArticlesApi], wiring together the three pieces a Retrofit setup always
 * needs: an HTTP client, a base URL, and a converter that turns JSON bytes into Kotlin objects.
 * Kept as a small factory object (rather than constructing these pieces inline wherever an
 * [ArticlesApi] is needed) so [MainActivity][com.neverlate.MainActivity] has one obvious place to
 * call, and so tests can point the same wiring at a fake server (see [create]'s `baseUrl`
 * parameter).
 */
object ArticlesNetwork {

    /**
     * Builds an [ArticlesApi] pointed at [baseUrl].
     *
     * [baseUrl] defaults to [DEFAULT_BASE_URL] — a **static JSON file served from this very
     * repository's `docs/articles-api/articles.json`, via GitHub's "raw" file server** (plain
     * HTTPS, no auth, no API key). That endpoint only starts responding once this branch is
     * merged and pushed to `master` (GitHub raw serves whatever is currently on that branch), so
     * this app cannot fetch real data from it until then. Unit/integration tests never depend on
     * that: they pass their own `baseUrl` pointing at a local `MockWebServer` instance instead,
     * which is exactly why this parameter exists rather than being hardcoded.
     */
    fun create(baseUrl: String = DEFAULT_BASE_URL): ArticlesApi {
        // HttpLoggingInterceptor prints every request/response OkHttp makes — invaluable while
        // learning networking, but BODY-level logging writes full request/response contents to
        // Logcat, which is too verbose (and, in a real app with sensitive data, unsafe) for a
        // release build. BuildConfig.DEBUG is a compile-time constant Gradle generates per build
        // type, so the `else` branch (NONE) is what release builds actually ship with.
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        // OkHttp is the actual HTTP engine underneath Retrofit; an "interceptor" is OkHttp's
        // mechanism for observing (or modifying) every request/response that passes through this
        // client, which is how the logging above gets wired in.
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .build()

        // ignoreUnknownKeys means a server that later adds a new JSON field to its response
        // (e.g. an "author") does not break parsing on this side — only fields this DTO actually
        // declares are read, everything else is silently skipped.
        val json = Json { ignoreUnknownKeys = true }

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            // asConverterFactory bridges kotlinx.serialization's Json into the
            // Converter.Factory shape Retrofit expects, so the same JSON library already used
            // elsewhere in the project (see kotlinx-serialization-json in the version catalog)
            // also deserializes network responses; the media type argument tells Retrofit which
            // Content-Type this converter applies to.
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(ArticlesApi::class.java)
    }
}
