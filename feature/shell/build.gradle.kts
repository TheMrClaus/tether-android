// Main shell: the phone layout (topbar, workspace header, drawer, telemetry panel) and the expanded
// desktop layout (T4.2: rail | workspace | inspector columns, resize handles), hosting
// :feature:sidebar and :feature:chat, plus toasts.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // T4.1 + T4.3: JVM screenshot tests of the shell states and the statusline components (recordRoborazziDebug / verifyRoborazziDebug).
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.tether.app.feature.shell"
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

// T4.1 + T4.3: goldens live in the source tree (checked in), one PNG per state × skin × size; verifyRoborazziDebug fails on any difference.
roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}
tasks.named("check") { dependsOn("verifyRoborazziDebug") }

dependencies {
    implementation(project(":feature:chat"))
    implementation(project(":feature:sidebar"))
    implementation(project(":core:protocol"))
    implementation(project(":core:net"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    // T4.3: the faithful lib/format.ts port (protocol.helpers.Format) and the projection views.
    implementation(project(":core:reducer"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // The v128 fold + event builders, so the mapping tests read real folded projections.
    testImplementation(testFixtures(project(":core:reducer")))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(composeBom)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
