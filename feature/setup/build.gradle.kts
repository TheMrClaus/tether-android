// T10.6 (ta-jwbs): the first-run setup wizard (tether 90fbb9f app/setup/page.tsx): Welcome, Operator,
// Harnesses, Workspace, [GitHub, Claude accounts: ta-pqui], Review and the restart step. Opened by the
// sign-in screen for a server whose /healthz says `setupRequired: true`.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // JVM screenshot tests of every step (recordRoborazziDebug / verifyRoborazziDebug).
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.tether.app.feature.setup"
    buildFeatures {
        compose = true
    }
    testOptions {
        // Robolectric (screenshot + behaviour tests) needs the merged resources: fonts, the test activity.
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Goldens live in the source tree (checked in); verifyRoborazziDebug fails on any difference.
roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}
tasks.named("check") { dependsOn("verifyRoborazziDebug") }

dependencies {
    implementation(project(":core:net"))
    implementation(project(":core:protocol"))
    implementation(project(":core:designsystem"))
    // The shared folder picker (T8.2) lives in :feature:settings (the sidebar and the shell depend on it too).
    implementation(project(":feature:settings"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // The wizard's state outlives a rotation in memory (the typed password is never written to disk).
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.lucide.icons)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The model and the screen over the real HttpSetupApi against a MockWebServer shaped from setup-server.mjs.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(composeBom)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
