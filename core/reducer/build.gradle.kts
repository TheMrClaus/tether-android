// Pure Kotlin/JVM: the session reducer and the derived models built on it.
// Test fixtures (ReducerTestSupport: ev/fold/freshState) are shared with
// other modules' tests via testFixtures(project(":core:reducer")).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
    `java-test-fixtures`
}

dependencies {
    api(project(":core:protocol"))

    testImplementation(libs.junit)
}
