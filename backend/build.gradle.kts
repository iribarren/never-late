// Backend Gradle build — an independent JVM project (Kotlin + Ktor), deliberately not wired into
// the root Android `settings.gradle.kts`. Run it via this module's own wrapper: `./gradlew build`
// from inside `backend/` (see backend/README.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    // Bundles the app + all dependencies into a single runnable "fat" jar (`shadowJar` task),
    // which is what the Dockerfile packages — no need to ship a JRE-less classpath by hand.
    // Pinned to 9.2.2, not the latest 9.5.x: shadow 9.3.0+ requires Gradle 9.0, but this project
    // pins Gradle 8.13 (gradle/wrapper/gradle-wrapper.properties) to match the root app build.
    // 9.2.2 is the newest release still compatible with Gradle 8.11+.
    id("com.gradleup.shadow") version "9.2.2"
}

group = "com.neverlate.backend"
version = "1.0.0"

repositories {
    mavenCentral()
}

application {
    // Entry point: see src/main/kotlin/com/neverlate/backend/Application.kt
    mainClass.set("com.neverlate.backend.ApplicationKt")
}

dependencies {
    // --- Ktor server: Netty engine, JSON content negotiation, JWT auth, error handling ---
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.logback.classic)

    // --- Auth: JWT signing (java-jwt, the library ktor-server-auth-jwt verifies tokens with)
    //     and bcrypt password hashing (jbcrypt — small, stable, no-frills API) ---
    implementation(libs.java.jwt)
    implementation(libs.jbcrypt)

    // --- Persistence: plain JDBC + HikariCP pool (no ORM; see README for why this project
    //     teaches raw SQL instead of Exposed) ---
    implementation(libs.hikaricp)
    implementation(libs.postgresql.driver)

    // --- Tests: testApplication (Ktor's in-process test server) + in-memory repository fakes
    //     (see auth/InMemoryUserRepository.kt, tasks/InMemoryTaskRepository.kt) — no Docker/DB
    //     needed to run the test suite. ---
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
