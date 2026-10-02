// T9.3 Scheduled actions (components/scheduled-actions-view.tsx): the console's Scheduled
// destination — fresh-agent cron schedules (list, create, edit, pause/resume, run now, delete, the
// last run) and the session-owned usage-limit continuations, over the v87 frames.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // JVM screenshot tests of the Scheduled states (recordRoborazziDebug / verifyRoborazziDebug).
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.tether.app.feature.scheduled"
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

// The wire tests replay the fake-engine capture (parity-corpus/wire/scheduled-actions.jsonl).
tasks.withType<Test>().configureEach {
    val corpus = rootProject.layout.projectDirectory.dir("parity-corpus/wire")
    inputs.dir(corpus).withPropertyName("parityCorpusWire").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("tether.parityCorpusWire", corpus.asFile.absolutePath)
}

dependencies {
    implementation(project(":core:net"))
    implementation(project(":core:protocol"))
    implementation(project(":core:reducer"))
    implementation(project(":core:designsystem"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.lucide.icons)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(composeBom)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
