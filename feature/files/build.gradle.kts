// T11.1 workspace file browser (components/workspace-file-browser.tsx): the /api/files browser
// dialog, its previews, uploads through the system picker and saving/sharing out.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // JVM screenshot tests of the browser states (recordRoborazziDebug / verifyRoborazziDebug).
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.tether.app.feature.files"
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

// Goldens live in the source tree (checked in), one PNG per state x skin x size; verifyRoborazziDebug fails on any difference.
roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}
tasks.named("check") { dependsOn("verifyRoborazziDebug") }

dependencies {
    implementation(project(":core:net"))
    // jsToFixed (the web's formatSize rounding).
    implementation(project(":core:reducer"))
    implementation(project(":core:designsystem"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    // The browser outlives a configuration change in a ViewModel (ta-u2n).
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // FileProvider (sharing a downloaded copy out of the app cache).
    implementation(libs.androidx.core)
    implementation(libs.lucide.icons)
    implementation(libs.kotlinx.coroutines.android)
    // The player pauses when the app stops (ta-1u4).
    implementation(libs.androidx.lifecycle.runtime.compose)
    // SVG preview (ta-1u4): script-free, no external fetch; see SvgImages.
    implementation(libs.androidsvg)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
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
