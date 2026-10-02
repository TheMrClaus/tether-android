// Login / pairing screen.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // T10.5: JVM screenshot tests of the passkey sign-in states (recordRoborazziDebug / verifyRoborazziDebug).
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.tether.app.feature.auth"
    buildFeatures {
        compose = true
    }
    testOptions {
        // Robolectric (LoginScreenBehaviourTest) needs the merged resources: fonts, the test activity.
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
    implementation(project(":core:designsystem"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.lucide.icons)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // ta-s4r: the real screen over the real client against a MockWebServer "Tether".
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
