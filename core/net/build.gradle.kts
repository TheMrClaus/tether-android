// The Tether client (HTTP + WebSocket) and the ViewModel the features share.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tether.app.core.net"
}

dependencies {
    api(project(":core:protocol"))
    api(project(":core:data"))
    implementation(project(":core:reducer"))

    api(libs.okhttp)
    api(libs.androidx.lifecycle.viewmodel)
    // TetherViewModel.errorLog is a SnapshotStateList (runtime only, no compiler plugin).
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(testFixtures(project(":core:reducer")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// T1.3: PendingInputTest replays parity-corpus/helpers/pending-input.json through the typed facade.
tasks.withType<Test>().configureEach {
    val corpusDir = rootProject.layout.projectDirectory.dir("parity-corpus")
    systemProperty("parity.corpus", corpusDir.asFile.absolutePath)
    inputs.dir(corpusDir.dir("helpers")).withPropertyName("parityHelperCorpus").withPathSensitivity(PathSensitivity.RELATIVE)
}
