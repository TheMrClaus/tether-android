// Pure Kotlin/JVM: the wire types (Wire, AgentEvent, ClientMessage,
// ServerMessage) and the projection model. No Android imports allowed here.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.lint)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    // T2.1: the persistent JsValue tree (tree/JsValue.kt) the v128 fold operates on.
    api(libs.kotlinx.collections.immutable)

    testImplementation(libs.junit)
}

// WireConformanceTest reads the vendored corpus (parity-corpus/wire) and the
// parity matrix. Optional, local-only: TETHER_PROTOCOL_TS / TETHER_PROTOCOL_VALIDATE
// point at a tether checkout's lib/protocol.ts / lib/protocol-validate.mjs
// (CI has no tether checkout; those checks are skipped there).
tasks.withType<Test>().configureEach {
    val corpus = rootProject.file("parity-corpus/wire")
    val matrix = rootProject.file("docs/parity/matrix.json")
    inputs.dir(corpus).withPropertyName("parityCorpusWire")
    inputs.file(matrix).withPropertyName("parityMatrix")
    systemProperty("tether.parityCorpusWire", corpus.absolutePath)
    systemProperty("tether.parityMatrix", matrix.absolutePath)
    testLogging { showStandardStreams = true }
}
