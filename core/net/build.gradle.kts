// The Tether client (HTTP + WebSocket) and the ViewModel the features share.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tether.app.core.net"
    // T13.1: the journal-mirror tests (Room on Robolectric's SQLite) need the merged manifest.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":core:protocol"))
    api(project(":core:data"))
    implementation(project(":core:reducer"))

    api(libs.okhttp)
    api(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.android)
    // T10.5: passkey registration and sign-in (Credential Manager), behind the PasskeyAuthenticator seam.
    implementation(libs.androidx.credentials)

    testImplementation(testFixtures(project(":core:reducer")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

// T1.3: PendingInputTest replays parity-corpus/helpers/pending-input.json through the typed facade.
tasks.withType<Test>().configureEach {
    val corpusDir = rootProject.layout.projectDirectory.dir("parity-corpus")
    systemProperty("parity.corpus", corpusDir.asFile.absolutePath)
    inputs.dir(corpusDir.dir("helpers")).withPropertyName("parityHelperCorpus").withPathSensitivity(PathSensitivity.RELATIVE)
    // T5.1: SidebarSyncTest re-encodes the sidebar frames against parity-corpus/wire.
    inputs.dir(corpusDir.dir("wire")).withPropertyName("parityWireCorpus").withPathSensitivity(PathSensitivity.RELATIVE)
    // T13.1: JournalMirrorConformanceTest drives every reducer corpus case through the client + mirror.
    inputs.dir(corpusDir.dir("reducer")).withPropertyName("parityReducerCorpus").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(corpusDir.file("corpus-manifest.json")).withPropertyName("parityManifest").withPathSensitivity(PathSensitivity.RELATIVE)
    maxHeapSize = "2g"
}
