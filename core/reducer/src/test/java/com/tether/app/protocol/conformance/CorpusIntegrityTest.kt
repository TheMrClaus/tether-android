package com.tether.app.protocol.conformance

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.num
import com.tether.app.protocol.tree.str
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2.1 H0: corpus integrity (sha256 + protocolVersion) and the canonical serializer self-test. */
class CorpusIntegrityTest {

    private val manifest = CanonicalJson.manifest
    private val files = (manifest["files"] as JsArr).map { it as JsObj }

    @Test
    fun protocolVersionIs128() {
        assertEquals(128.0, manifest["protocolVersion"].num)
    }

    @Test
    fun everyManifestFileMatchesItsSha256() {
        val mismatches = files.mapNotNull { entry ->
            val path = entry["path"].str!!
            val file = File(CanonicalJson.corpusDir, path)
            if (!file.isFile) return@mapNotNull "$path: missing"
            val actual = CanonicalJson.sha256Hex(file.readBytes())
            if (actual == entry["sha256"].str) null else "$path: sha256 $actual != manifest ${entry["sha256"].str}"
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
        val reducerFiles = files.count { it["path"].str!!.startsWith("reducer/") }
        assertEquals(70, reducerFiles)
    }

    /** Parse every corpus file and write it back canonically: byte-for-byte identical. */
    @Test
    fun canonicalWriterReproducesEveryCorpusFileByteForByte() {
        val mismatches = files.mapNotNull { entry ->
            val path = entry["path"].str!!
            val bytes = File(CanonicalJson.corpusDir, path).readBytes()
            val text = String(bytes, Charsets.UTF_8)
            val rewritten = CanonicalJson.fileText(CanonicalJson.parse(text))
            if (rewritten.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
                null
            } else {
                val at = rewritten.zip(text).indexOfFirst { (a, b) -> a != b }.let { if (it < 0) minOf(rewritten.length, text.length) else it }
                "$path: first difference at char $at: file «${text.substring(maxOf(0, at - 40), minOf(text.length, at + 40))}» " +
                    "vs written «${rewritten.substring(maxOf(0, at - 40), minOf(rewritten.length, at + 40))}»"
            }
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    @Test
    fun referenceEventsMjsMatchesItsRecordedSha() {
        val dir = File(CanonicalJson.corpusDir, "reference")
        val recorded = File(dir, "SHA256").readText().trim().substringBefore(' ')
        assertEquals(recorded, CanonicalJson.sha256Hex(File(dir, "events.mjs").readBytes()))
        assertTrue(recorded.startsWith("6478d19c9758e122"))
    }
}
