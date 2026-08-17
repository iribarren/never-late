// Top-level build file. Plugins are declared here with `apply false` so that
// their versions are resolved once and applied in the module build files.
plugins {
    // org.jetbrains.kotlin.android is no longer applied by hand: AGP 9's built-in Kotlin
    // (android.builtInKotlin, on by default) supplies it. kotlin-compose and kotlin-serialization
    // are separate plugins built-in Kotlin does not cover, so they stay explicit.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt.android) apply false
}
