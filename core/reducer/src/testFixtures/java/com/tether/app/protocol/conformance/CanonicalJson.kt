package com.tether.app.protocol.conformance

import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsValue
import java.io.File
import java.security.MessageDigest

/**
 * T2.1 H0: the parity corpus's canonical JSON (corpus-manifest.json `canonicalJson`), shared by
 * the reducer conformance harness and T2.2's helper harness (which layers the
 * `$undefined`/`$map`/`$number` typed values on top of these plain trees).
 */
object CanonicalJson {

    /** The vendored corpus root, from the `parity.corpus` system property the build sets. */
    val corpusDir: File by lazy {
        val path = System.getProperty("parity.corpus")
            ?: error("system property parity.corpus is not set (core/reducer/build.gradle.kts sets it for Test tasks)")
        File(path).also { require(it.isDirectory) { "parity corpus not found at $it" } }
    }

    /** The repo root (parent of parity-corpus/), for tests that read sources. */
    val repoRoot: File get() = corpusDir.parentFile

    fun parse(text: String): JsValue = JsCodec.parse(text)

    fun read(file: File): JsValue = parse(file.readText(Charsets.UTF_8))

    /** Canonical text without the corpus files' trailing newline. */
    fun write(value: JsValue): String = JsCodec.canonical(value)

    /** Exactly what a corpus file holds: one canonical value and a single "\n". */
    fun fileText(value: JsValue): String = write(value) + "\n"

    val manifest: JsObj by lazy { read(File(corpusDir, "corpus-manifest.json")) as JsObj }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
