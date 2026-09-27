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

// T2.1 conformance harness: the vendored parity corpus and the reference events.mjs
// live at the repo root. `-Pparity.only=<glob>` narrows ReducerConformanceTest to
// matching case names; `-Pparity.strict=true` forces the event-type label coverage
// test to fail hard (unit I flips its committed default to strict).
tasks.withType<Test>().configureEach {
    val corpusDir = rootProject.layout.projectDirectory.dir("parity-corpus")
    systemProperty("parity.corpus", corpusDir.asFile.absolutePath)
    inputs.dir(corpusDir).withPropertyName("parityCorpus").withPathSensitivity(PathSensitivity.RELATIVE)
    providers.gradleProperty("parity.only").orNull?.let { systemProperty("parity.only", it) }
    providers.gradleProperty("parity.strict").orNull?.let { systemProperty("parity.strict", it) }
    maxHeapSize = "2g"
}
