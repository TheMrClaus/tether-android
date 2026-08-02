// Top-level build file. Plugin versions are managed in gradle/libs.versions.toml.
// Note: AGP 9.x has built-in Kotlin, so org.jetbrains.kotlin.android is not applied
// (it is rejected by AGP 9 unless android.builtInKotlin=false).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
