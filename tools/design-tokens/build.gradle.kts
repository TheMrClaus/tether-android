// PLAN D9 / T3.1: the design-token generator. Pure Kotlin/JVM; reads the vendored
// parity-corpus/tokens/design-tokens.json (tether scripts/export-design-tokens.mjs)
// and writes :core:designsystem's GeneratedTokens.kt. Run through the
// generateDesignTokens / verifyDesignTokens tasks in core/designsystem/build.gradle.kts.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
