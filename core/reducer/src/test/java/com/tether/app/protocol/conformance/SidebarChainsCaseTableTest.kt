package com.tether.app.protocol.conformance

import com.tether.app.protocol.conformance.CanonicalJson.TaggedObject
import com.tether.app.protocol.helpers.SidebarOrder
import com.tether.app.protocol.helpers.get
import com.tether.app.protocol.helpers.SidebarOrder.ChainMemberFacts
import com.tether.app.protocol.helpers.SidebarOrder.ChainSuffix
import com.tether.app.protocol.helpers.SidebarOrder.ChainSuffixKind
import com.tether.app.protocol.helpers.SidebarOrder.HandoverChainKeys
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * W35 (ta-b6m9) + W39 (ta-ftl7): cases[10..129] of tether 19c56e25's sidebar-order table (lib/sidebar-order.ts) = the 25
 * `handoverFoldCases` + the SB-2 `sequenceGroupCases` + the W28 nesting cases (120 in all), kept OUT of parity-corpus/ (whose
 * pins stay at 887c222 / v137) in src/test/resources/helpers/sidebar-chains-19c56e25.json, run through the SHARED HelperAdapters
 * (ta-d3tj.9); HelperCorpusIntegrityTest counts this file's fns as exercised. The Map result (`$map`) is encoded as the typed layer does.
 */
@RunWith(Parameterized::class)
class SidebarChainsCaseTableTest(private val index: Int, private val fn: String) {

    companion object {
        private const val RESOURCE = "/helpers/sidebar-chains-19c56e25.json"
        val table: JsObj by lazy { CanonicalJson.parse(SidebarChainsCaseTableTest::class.java.getResource(RESOURCE)!!.readText()) as JsObj }
        private val cases get() = (table["cases"] as JsArr).map { it as JsObj }

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun params(): List<Array<Any>> = cases.mapIndexed { i, c -> arrayOf<Any>(i, c["fn"].str!!) }
    }

    @Test
    fun matchesTheWeb() {
        val case = cases[index]
        // the SAME decode + adapter as HelperConformanceTest (HelperAdapters, table "sidebar-order")
        val args = HelperAdapters.Args((case["args"] as JsArr).map { CanonicalJson.decodeTagged(it) }, null, JsObj.EMPTY)
        val actual = CanonicalJson.encodeTagged(HelperAdapters.call("sidebar-order", fn, args))
        val diff = TreeDiff.diff(case["result"], actual, limit = 20)
        assertTrue(
            "$fn(${JsCodec.canonical(case["args"]!!)})\n  expected ${JsCodec.canonical(case["result"]!!)}\n  actual   ${JsCodec.canonical(actual)}\n" +
                diff.joinToString("\n") { it.render() },
            diff.isEmpty(),
        )
    }

    /** The file is the recorded 120 cases of 19c56e25, every fn has an adapter here, and none is orphaned. */
    @Test
    fun theCaseFileIsScopedAndPinned() {
        assertEquals("19c56e25eab4fdf75fb34bdf3e6962b9fc0ab074", table["tetherSha"].str)
        assertTrue((table["recipe"].str ?: "").isNotEmpty())
        assertEquals(64, (table["sourceSha256"].str ?: "").length)
        assertEquals(120, cases.size)
        val registered = HelperAdapters.keys.filter { it.startsWith("sidebar-order.") }.map { it.removePrefix("sidebar-order.") }.toSet()
        val webOnly = setOf("applySidebarOrder", "sortSidebarEntries", "moveSidebarEntry") // exercised by parity-corpus/helpers
        assertEquals(registered - webOnly, cases.map { it["fn"].str!! }.toSet())
    }
}
