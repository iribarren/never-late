package com.neverlate.data.articles

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.neverlate.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

/** Where the remote article catalog is hosted. See [create]'s KDoc for the full URL story. */
private const val DEFAULT_BASE_URL = BuildConfig.BACKEND_BASE_URL

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
     * [baseUrl] defaults to [DEFAULT_BASE_URL] — [BuildConfig.BACKEND_BASE_URL], this project's
     * own Ktor backend (`backend/`, feature 11). Through feature 13b, articles came from a
     * **static JSON file served over GitHub raw** instead (`docs/articles-api/articles.json`);
     * feature 13c retires that source in favour of a real, **paginated**, backend endpoint
     * (`GET /articles?page=&size=`, `docs/api/contract.md` §7) — the article catalog is now seeded
     * into the backend from that same JSON file, rather than fetched from it directly. Unlike
     * every other call this backend serves, `/articles` is **public**: it needs no `Authorization`
     * header at all (see [com.neverlate.data.network.BackendNetwork] for the auth-attaching
     * wiring `/tasks*`/`/auth*` use instead), which is why this endpoint keeps its own small,
     * separate Retrofit setup here rather than going through that shared helper.
     *
     * Unit/integration tests never depend on the real backend either: they pass their own
     * `baseUrl` pointing at a local `MockWebServer` instance instead, which is exactly why this
     * parameter exists rather than being hardcoded.
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
