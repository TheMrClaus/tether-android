package com.tether.app.protocol.conformance

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.num
import com.tether.app.protocol.tree.str
import java.io.File

/**
 * One helper corpus table (parity-corpus/helpers/<name>.json):
 * `{ helper, module, environment, constants?, notes?, skipped?, cases: [{ fn, args, nowMs?, result | throws }] }`.
 */
class HelperTable(val name: String, val root: JsObj) {
    val module: String = root["module"].str!!
    val constants: JsObj = root["constants"] as? JsObj ?: JsObj.EMPTY
    val skipped: List<Pair<String, String>> = (root["skipped"] as? JsArr).orEmpty().map { (it as JsObj)["fn"].str!! to it["reason"].str!! }
    val cases: List<HelperCase> = (root["cases"] as JsArr).mapIndexed { index, raw -> HelperCase(name, index, raw as JsObj) }

    companion object {
        val helpersDir: File get() = File(CanonicalJson.corpusDir, "helpers")

        fun names(): List<String> =
            helpersDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }!!.map { it.name.removeSuffix(".json") }.sorted()

        private val cache = java.util.concurrent.ConcurrentHashMap<String, HelperTable>()

        fun load(name: String): HelperTable = cache.getOrPut(name) {
            HelperTable(name, CanonicalJson.read(File(helpersDir, "$name.json")) as JsObj)
        }
    }
}

/** One recorded call. [args] are decoded through the typed layer ([CanonicalJson.decodeTagged]). */
class HelperCase(val table: String, val index: Int, val raw: JsObj) {
    val fn: String = raw["fn"].str!!
    val args: List<Any?> = (raw["args"] as JsArr).map { CanonicalJson.decodeTagged(it) }
    val nowMs: Double? = raw["nowMs"].num
    /** The expected result in the typed layer, as stored (null when the case throws). */
    val result: JsValue? = raw["result"]
    val throws: JsObj? = raw["throws"] as? JsObj
}
