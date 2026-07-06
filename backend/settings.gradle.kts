// Backend sub-project's own Gradle settings — deliberately separate from the root
// `settings.gradle.kts` (which only knows about the Android `app` module). This keeps the
// backend's build (JVM, Ktor) fully independent from the Android/Gradle-plugin build, so neither
// side needs to understand the other's tooling.
rootProject.name = "never-late-backend"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
