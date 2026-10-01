package com.tether.app.protocol.conformance

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.num
import com.tether.app.protocol.tree.str
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2.2: integrity of the vendored helper corpus (the CorpusIntegrityTest checks, scoped to
 * helpers/) plus the harness's no-silent-skip guarantees: every recorded fn has a Kotlin adapter,
 * every adapter is exercised, and each table's exporter-side `skipped` list is exactly the set this
 * port knows about (host-locale branches → HelperLocaleBranchesTest; browser/localStorage I/O →
 * no pure counterpart).
 */
class HelperCorpusIntegrityTest {

    private val manifest = CanonicalJson.manifest
    private val helperFiles = (manifest["files"] as JsArr).map { it as JsObj }.filter { it["kind"].str == "helper" }

    @Test
    fun manifestPinsProtocolAndTetherSha() {
        assertEquals(137.0, manifest["protocolVersion"].num)
        assertEquals("887c22214126fa662192e2a3adf6f2dd44e69cd6", manifest["tetherSha"].str)
        val environment = (manifest["helpers"] as JsObj)["environment"] as JsObj
        assertEquals("UTC", environment["timeZone"].str)
        assertEquals(1790078400000.0, environment["referenceNowMs"].num)
    }

    @Test
    fun helperFilesMatchTheirSha256AndCaseCounts() {
        assertEquals(25, helperFiles.size)
        assertEquals(HelperTable.names().map { "helpers/$it.json" }, helperFiles.map { it["path"].str!! }.sorted())
        val mismatches = helperFiles.mapNotNull { entry ->
            val path = entry["path"].str!!
            val file = File(CanonicalJson.corpusDir, path)
            val sha = CanonicalJson.sha256Hex(file.readBytes())
            val cases = HelperTable.load(file.name.removeSuffix(".json")).cases.size
            when {
                sha != entry["sha256"].str -> "$path: sha256 $sha != manifest ${entry["sha256"].str}"
                cases.toDouble() != entry["cases"].num -> "$path: $cases cases != manifest ${entry["cases"].num}"
                else -> null
            }
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
        assertEquals(807, HelperTable.names().sumOf { HelperTable.load(it).cases.size })
    }

    @Test
    fun everyRecordedFnHasAnAdapterAndEveryAdapterIsExercised() {
        val recorded = HelperTable.names().flatMap { name -> HelperTable.load(name).cases.map { "$name.${it.fn}" } }.toSet()
        assertEquals("fns with no Kotlin adapter", emptySet<String>(), recorded - HelperAdapters.keys)
        assertEquals("adapters no case exercises", emptySet<String>(), HelperAdapters.keys - recorded)
    }

    /** The exporter's own skip notes, each accounted for (never a silent gap). */
    @Test
    fun exporterSkipsAreExactlyTheKnownOnes() {
        val expected = mapOf(
            "attachment-draft" to setOf("readFileAsBase64", "prepareAttachmentData"), // browser FileReader/canvas I/O
            "claude-reset-grants-view" to setOf("claimOutcomeCopy"), // weekly-reset sentence: host locale → HelperLocaleBranchesTest
            "draft-preferences" to setOf("readDraftPreferences", "writeDraftPreferences"), // localStorage I/O → T2.3
            "format" to setOf("absoluteTime", "clockTime", "relativeTime"), // host-locale branches → HelperLocaleBranchesTest
        )
        val actual = HelperTable.names().associateWith { name -> HelperTable.load(name).skipped.map { it.first }.toSet() }.filterValues { it.isNotEmpty() }
        assertEquals(expected, actual)
        val skippedModules = ((manifest["helpers"] as JsObj)["skippedModules"] as JsArr).map { (it as JsObj)["module"].str }
        assertEquals(
            listOf("lib/session-order.mjs", "lib/session-seen.mjs", "components/session-sidebar.tsx (hasUnseenWork, hasWorkInProgress, grouping)"),
            skippedModules,
        )
    }
}
