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
 * W35 (ta-b6m9): the 25 `handoverFoldCases` of tether 7cc0f38c (lib/sidebar-order.ts), kept OUT of parity-corpus/ (whose
 * pins stay at 887c222 / v137) in src/test/resources/helpers/sidebar-chains-7cc0f38c.json, run through their OWN adapter
 * table so HelperAdapters' exercised-set check is unchanged. The Map result (`$map`) is encoded as the typed layer does.
 */
@RunWith(Parameterized::class)
class SidebarChainsCaseTableTest(private val index: Int, private val fn: String) {

    companion object {
        private const val RESOURCE = "/helpers/sidebar-chains-7cc0f38c.json"
        val table: JsObj by lazy { CanonicalJson.parse(SidebarChainsCaseTableTest::class.java.getResource(RESOURCE)!!.readText()) as JsObj }
        private val cases get() = (table["cases"] as JsArr).map { it as JsObj }

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun params(): List<Array<Any>> = cases.mapIndexed { i, c -> arrayOf<Any>(i, c["fn"].str!!) }

        private fun chainKeys(v: JsValue?): List<HandoverChainKeys> = (v as JsArr).map { c ->
            HandoverChainKeys((c["headKey"] as JsStr).value, (c["olderKeys"] as JsArr).map { (it as JsStr).value })
        }

        private fun facts(v: JsValue?): List<ChainMemberFacts> = (v as JsArr).map { m ->
            fun b(k: String) = m[k] == JsBool.TRUE
            ChainMemberFacts((m["key"] as JsStr).value, b("selected"), b("waiting"), b("active"), b("unread"))
        }

        private fun strings(v: JsValue?): List<String> = (v as JsArr).map { (it as JsStr).value }

        private fun suffix(v: JsValue?): ChainSuffix? = (v as? JsObj)?.let {
            ChainSuffix(
                when ((it["kind"] as JsStr).value) { "waiting" -> ChainSuffixKind.Waiting; "active" -> ChainSuffixKind.Active; else -> ChainSuffixKind.New },
                (it["count"] as JsNum).value.toInt(),
            )
        }

        /** The adapter table of THIS case file (never HelperAdapters). */
        private val adapters: Map<String, (List<JsValue?>) -> Any?> = mapOf(
            "handoverChains" to { a ->
                SidebarOrder.handoverChains((a[0] as JsArr).toList()).map { TaggedObject(mapOf("head" to it.head, "older" to JsArr.of(it.older))) }
            },
            "foldHandoverChains" to { a ->
                val r = SidebarOrder.foldHandoverChains((a[0] as JsArr).toList())
                TaggedObject(mapOf("rows" to JsArr.of(r.rows), "olderByHeadKey" to r.olderByHeadKey.mapValues { JsArr.of(it.value) }))
            },
            "collapseChainOrder" to { a -> SidebarOrder.collapseChainOrder(strings(a[0]), chainKeys(a[1])).map { JsStr(it) } },
            "expandChainOrder" to { a -> SidebarOrder.expandChainOrder(strings(a[0]), chainKeys(a[1])).map { JsStr(it) } },
            "summarizeChainMembers" to { a ->
                val r = SidebarOrder.summarizeChainMembers(facts(a[0]))
                TaggedObject(mapOf(
                    "reasonKeys" to r.reasonKeys.map { JsStr(it) },
                    "suffix" to r.suffix?.let { TaggedObject(mapOf("kind" to JsStr(it.kind.name.lowercase().let { k -> if (k == "waiting") "waiting" else k }), "count" to JsNum(it.count.toDouble()))) },
                ))
            },
            "chainToggleLabel" to { a -> JsStr(SidebarOrder.chainToggleLabel((a[0] as JsNum).value.toInt())) },
            "chainSuffixText" to { a -> JsStr(SidebarOrder.chainSuffixText(suffix(a[0]))) },
        )
    }

    @Test
    fun matchesTheWeb() {
        val case = cases[index]
        val args = (case["args"] as JsArr).toList()
        val actual = CanonicalJson.encodeTagged(adapters.getValue(fn)(args))
        val diff = TreeDiff.diff(case["result"], actual, limit = 20)
        assertTrue(
            "$fn(${JsCodec.canonical(case["args"]!!)})\n  expected ${JsCodec.canonical(case["result"]!!)}\n  actual   ${JsCodec.canonical(actual)}\n" +
                diff.joinToString("\n") { it.render() },
            diff.isEmpty(),
        )
    }

    /** The file is the recorded 25 cases of 7cc0f38c, every fn has an adapter here, and none is orphaned. */
    @Test
    fun theCaseFileIsScopedAndPinned() {
        assertEquals("7cc0f38ce237d446843f6f9cabaa83ab89f67532", table["tetherSha"].str)
        assertTrue((table["recipe"].str ?: "").isNotEmpty())
        assertEquals(64, (table["sourceSha256"].str ?: "").length)
        assertEquals(25, cases.size)
        assertEquals(adapters.keys, cases.map { it["fn"].str!! }.toSet())
    }
}
