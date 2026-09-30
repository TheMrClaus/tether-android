// Chat screen: timeline, cards, tool cards, composer, chat controls.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // T6.1: JVM screenshot tests of the transcript states (recordRoborazziDebug / verifyRoborazziDebug).
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.tether.app.feature.chat"
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

// T6.1: goldens live in the source tree (checked in), one PNG per state × skin × size; verifyRoborazziDebug fails on any difference.
roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}
tasks.named("check") { dependsOn("verifyRoborazziDebug") }

dependencies {
    implementation(project(":core:net"))
    implementation(project(":core:reducer"))
    implementation(project(":core:data"))
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
    // The v128 fold + event builders, so transcript tests read real folded projections.
    testImplementation(testFixtures(project(":core:reducer")))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(composeBom)
    testImplementation(libs.robolectric)
    // T6.8: the wire-to-screen media test drives the real HttpToolMedia against a replayed server.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}

// T6.2: tool-card fixtures fold the vendored reducer corpus (parity-corpus/reducer).
tasks.withType<Test>().configureEach {
    val corpusDir = rootProject.layout.projectDirectory.dir("parity-corpus")
    systemProperty("parity.corpus", corpusDir.asFile.absolutePath)
    inputs.dir(corpusDir.dir("reducer")).withPropertyName("parityReducerCorpus").withPathSensitivity(PathSensitivity.RELATIVE)
}
